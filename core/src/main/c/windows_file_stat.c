/* CRT-free JNI: a handle-based Windows metadata query for identity questions.
 *
 * Opens the requested path once with FILE_READ_ATTRIBUTES and FILE_FLAG_OPEN_REPARSE_POINT, then
 * answers from handle-based metadata queries. FileBasicInfo supplies the timestamps and attributes
 * used by this receipt (creation, last-access, last-write, change); BY_HANDLE_FILE_INFORMATION
 * supplies size, volume serial, and the legacy 64-bit file index; FileAttributeTagInfo supplies
 * the reparse tag when present. Java never re-opens the pathname for these identity questions, and
 * the metadata queries are not an atomic snapshot. The legacy volume+index identity pair is
 * intended for NTFS; filesystems whose 64-bit file-ID semantics are weaker, such as ReFS, are
 * outside this contract.
 *
 * Rebuild via core/src/main/c/rebuild-windows-natives.sh (needs mingw-w64 and JAVA_HOME).
 * The output must stay byte-identical on every rebuild: --no-insert-timestamp and the fixed
 * --image-base pin the PE header, and the output name must remain win_natives.dll because
 * mingw embeds it as the DLL's internal export name. CI rebuilds and compares against the
 * committed binary, so sources and DLL cannot drift apart silently.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>
#include "windows_jni_path.h"

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
	(void) instance;
	(void) reason;
	(void) reserved;
	return TRUE;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
	(void) vm;
	(void) reserved;
	return JNI_VERSION_1_8;
}

/* JNI encodes '_' in automodpack_core as _1. javac -h emits Java_pl_skidam_automodpack_1core_utils_cache_WindowsFileStat_read0. */
JNIEXPORT jboolean JNICALL Java_pl_skidam_automodpack_1core_utils_cache_WindowsFileStat_read0(JNIEnv *env, jclass cls, jstring jpath, jlongArray out) {
	WCHAR stack[WIN32_JNI_STACK_PATH_CHARS];
	WCHAR *wpath;
	int heap;
	HANDLE handle;
	FILE_BASIC_INFO basic;
	BY_HANDLE_FILE_INFORMATION info;
	unsigned long long file_id;
	jlong values[8];
	(void) cls;
	if (jpath == NULL || out == NULL || (*env)->GetArrayLength(env, out) < 8) return JNI_FALSE;
	if (win32_jni_path(env, jpath, stack, WIN32_JNI_STACK_PATH_CHARS, &wpath, &heap) != 0) return JNI_FALSE;
	/* OPEN_REPARSE_POINT makes a symbolic-link path open the link itself rather than its target. */
	handle = CreateFileW(wpath, FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
	if (heap) HeapFree(GetProcessHeap(), 0, wpath);
	if (handle == INVALID_HANDLE_VALUE) return JNI_FALSE;
	if (!GetFileInformationByHandleEx(handle, FileBasicInfo, &basic, sizeof(basic)) || !GetFileInformationByHandle(handle, &info)) {
		CloseHandle(handle);
		return JNI_FALSE;
	}
	DWORD reparseTag = 0;
	if ((basic.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
		/* A reparse point is not a symlink - the tag identifies the kind (symlink, junction, dedup, cloud
		 * placeholder). A failed tag query fails the whole stat: Java must not read a reparse file as an
		 * ordinary one just because its tag was unavailable. */
		FILE_ATTRIBUTE_TAG_INFO tag;
		if (!GetFileInformationByHandleEx(handle, FileAttributeTagInfo, &tag, sizeof(tag))) {
			CloseHandle(handle);
			return JNI_FALSE;
		}
		reparseTag = tag.ReparseTag;
	}
	CloseHandle(handle);
	file_id = ((unsigned long long) info.nFileIndexHigh << 32) | (unsigned long long) info.nFileIndexLow;
	/* FileBasicInfo supplies the timestamps/attributes used by this receipt; BY_HANDLE adds size,
	 * volume serial, and legacy index; FileAttributeTagInfo supplies raw[7] for reparse points. */
	values[0] = basic.ChangeTime.QuadPart;
	values[1] = basic.LastWriteTime.QuadPart;
	values[2] = basic.CreationTime.QuadPart;
	values[3] = ((unsigned long long) info.nFileSizeHigh << 32) | (unsigned long long) info.nFileSizeLow;
	values[4] = (jlong) info.dwVolumeSerialNumber;
	values[5] = (jlong) file_id;
	values[6] = basic.FileAttributes;
	values[7] = (jlong) reparseTag;
	(*env)->SetLongArrayRegion(env, out, 0, 8, values);
	if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
	return JNI_TRUE;
}
