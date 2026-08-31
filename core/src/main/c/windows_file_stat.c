/* CRT-free JNI: NTFS ChangeTime and volume+file index via KERNEL32.
 *
 * Rebuild:
 *   x86_64-w64-mingw32-gcc -c -O0 -fno-PIC -fno-ident -o /tmp/pe_reloc.o core/src/main/c/pe_reloc.c
 *   x86_64-w64-mingw32-gcc -shared -s -Os -fno-ident -fPIC -fno-stack-protector -fno-asynchronous-unwind-tables -fno-unwind-tables -nostdlib -Wl,--gc-sections -Wl,--enable-reloc-section -Wl,--dynamicbase -Wl,--high-entropy-va -Wl,-e,DllMain -Wl,-u,win_file_stat_keep -I core/src/main/c -I "$JAVA_HOME/include" -o core/src/main/resources/natives/windows-x86_64/win_file_stat.dll core/src/main/c/windows_file_stat.c /tmp/pe_reloc.o -lkernel32
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>

/* MAX_PATH is 260 including NUL. CreateFileW with "\\?\" allows 32767 characters.
 * Stack holds a classic MAX_PATH path after "\\?\" (4) or "\\?\UNC\" (8, replacing "\\"): MAX_PATH + 6. */
#define EXTENDED_PATH_CHARS 32767
#define STACK_PATH_CHARS (MAX_PATH + 6)

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

static int leave_as_is(const jchar *chars, jsize len) {
	return len >= 4 && chars[0] == '\\' && chars[1] == '\\' && (chars[2] == '?' || chars[2] == '.') && chars[3] == '\\';
}

static int is_unc(const jchar *chars, jsize len) {
	return len >= 2 && chars[0] == '\\' && chars[1] == '\\' && !leave_as_is(chars, len);
}

static int is_drive_absolute(const jchar *chars, jsize len) {
	jchar drive;
	if (len < 3 || chars[1] != ':') return 0;
	if (chars[2] != '\\' && chars[2] != '/') return 0;
	drive = chars[0];
	return (drive >= 'A' && drive <= 'Z') || (drive >= 'a' && drive <= 'z');
}

/* JNI encodes '_' in automodpack_core as _1. javac -h emits Java_pl_skidam_automodpack_1core_utils_cache_WindowsFileStat_read0. */
JNIEXPORT jboolean JNICALL Java_pl_skidam_automodpack_1core_utils_cache_WindowsFileStat_read0(JNIEnv *env, jclass cls, jstring jpath, jlongArray out) {
	const jchar *chars;
	jsize len;
	jsize src;
	jsize dst;
	jsize prefix;
	jsize total;
	WCHAR stack[STACK_PATH_CHARS];
	WCHAR *wpath;
	WCHAR ch;
	int heap;
	HANDLE handle;
	FILE_BASIC_INFO basic;
	BY_HANDLE_FILE_INFORMATION info;
	unsigned long long file_id;
	jlong values[3];
	(void) cls;
	if (jpath == NULL || out == NULL || (*env)->GetArrayLength(env, out) < 3) return JNI_FALSE;
	len = (*env)->GetStringLength(env, jpath);
	if (len <= 0 || len > EXTENDED_PATH_CHARS) return JNI_FALSE;
	chars = (*env)->GetStringChars(env, jpath, NULL);
	if (chars == NULL) return JNI_FALSE;
	if (leave_as_is(chars, len)) prefix = 0;
	else if (is_unc(chars, len)) prefix = 8;
	else if (is_drive_absolute(chars, len)) prefix = 4;
	else prefix = 0;
	total = prefix == 8 ? prefix + len - 1 : prefix + len + 1;
	if (total < 2 || total > EXTENDED_PATH_CHARS + 1) {
		(*env)->ReleaseStringChars(env, jpath, chars);
		return JNI_FALSE;
	}
	heap = 0;
	wpath = stack;
	if (total > STACK_PATH_CHARS) {
		wpath = (WCHAR *) HeapAlloc(GetProcessHeap(), 0, (SIZE_T) total * sizeof(WCHAR));
		if (wpath == NULL) {
			(*env)->ReleaseStringChars(env, jpath, chars);
			return JNI_FALSE;
		}
		heap = 1;
	}
	dst = 0;
	if (prefix == 4) {
		wpath[0] = '\\';
		wpath[1] = '\\';
		wpath[2] = '?';
		wpath[3] = '\\';
		dst = 4;
		src = 0;
	} else if (prefix == 8) {
		wpath[0] = '\\';
		wpath[1] = '\\';
		wpath[2] = '?';
		wpath[3] = '\\';
		wpath[4] = 'U';
		wpath[5] = 'N';
		wpath[6] = 'C';
		wpath[7] = '\\';
		dst = 8;
		src = 2;
	} else {
		src = 0;
	}
	while (src < len) {
		ch = (WCHAR) chars[src++];
		if (ch == '/') ch = '\\';
		wpath[dst++] = ch;
	}
	wpath[dst] = 0;
	(*env)->ReleaseStringChars(env, jpath, chars);
	/* OPEN_REPARSE_POINT matches Java LinkOption.NOFOLLOW_LINKS: identity of the directory entry, not the target. */
	handle = CreateFileW(wpath, FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
	if (heap) HeapFree(GetProcessHeap(), 0, wpath);
	if (handle == INVALID_HANDLE_VALUE) return JNI_FALSE;
	if (!GetFileInformationByHandleEx(handle, FileBasicInfo, &basic, sizeof(basic)) || !GetFileInformationByHandle(handle, &info)) {
		CloseHandle(handle);
		return JNI_FALSE;
	}
	CloseHandle(handle);
	file_id = ((unsigned long long) info.nFileIndexHigh << 32) | (unsigned long long) info.nFileIndexLow;
	values[0] = basic.ChangeTime.QuadPart;
	values[1] = (jlong) info.dwVolumeSerialNumber;
	values[2] = (jlong) file_id;
	(*env)->SetLongArrayRegion(env, out, 0, 3, values);
	if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
	return JNI_TRUE;
}
