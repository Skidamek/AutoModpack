/* CRT-free JNI path: a NUL-terminated Win32 path from a Java string, with \\?\ when needed.
 *
 * GetStringChars is not required to be NUL-terminated; CreateFileW and RmRegisterResources are.
 * Drive-absolute and UNC paths get the extended prefix so they are not capped at MAX_PATH.
 * MAX_PATH is 260 including NUL. CreateFileW with "\\?\" allows 32767 characters. Stack holds a
 * classic MAX_PATH path after "\\?\" (4) or "\\?\UNC\" (8, replacing "\\"): MAX_PATH + 6.
 */
#ifndef WINDOWS_JNI_PATH_H
#define WINDOWS_JNI_PATH_H

#define WIN32_JNI_EXTENDED_PATH_CHARS 32767
#define WIN32_JNI_STACK_PATH_CHARS (MAX_PATH + 6)

static int win32_jni_leave_as_is(const jchar *chars, jsize len) {
	return len >= 4 && chars[0] == '\\' && chars[1] == '\\' && (chars[2] == '?' || chars[2] == '.') && chars[3] == '\\';
}

static int win32_jni_is_unc(const jchar *chars, jsize len) {
	return len >= 2 && chars[0] == '\\' && chars[1] == '\\' && !win32_jni_leave_as_is(chars, len);
}

static int win32_jni_is_drive_absolute(const jchar *chars, jsize len) {
	jchar drive;
	if (len < 3 || chars[1] != ':') return 0;
	if (chars[2] != '\\' && chars[2] != '/') return 0;
	drive = chars[0];
	return (drive >= 'A' && drive <= 'Z') || (drive >= 'a' && drive <= 'z');
}

/* On success *wpath is a NUL-terminated Win32 path. *heap is 1 when *wpath was HeapAlloc'd (caller HeapFree).
 * Returns 0 on success, -1 on failure. stack is used when the result fits in stack_chars. */
static int win32_jni_path(JNIEnv *env, jstring jpath, WCHAR *stack, jsize stack_chars, WCHAR **wpath, int *heap) {
	const jchar *chars;
	jsize len;
	jsize src;
	jsize dst;
	jsize prefix;
	jsize total;
	WCHAR ch;
	if (jpath == NULL || stack == NULL || wpath == NULL || heap == NULL) return -1;
	len = (*env)->GetStringLength(env, jpath);
	if (len <= 0 || len > WIN32_JNI_EXTENDED_PATH_CHARS) return -1;
	chars = (*env)->GetStringChars(env, jpath, NULL);
	if (chars == NULL) return -1;
	if (win32_jni_leave_as_is(chars, len)) prefix = 0;
	else if (win32_jni_is_unc(chars, len)) prefix = 8;
	else if (win32_jni_is_drive_absolute(chars, len)) prefix = 4;
	else prefix = 0;
	total = prefix == 8 ? prefix + len - 1 : prefix + len + 1;
	if (total < 2 || total > WIN32_JNI_EXTENDED_PATH_CHARS + 1) {
		(*env)->ReleaseStringChars(env, jpath, chars);
		return -1;
	}
	*heap = 0;
	*wpath = stack;
	if (total > stack_chars) {
		*wpath = (WCHAR *) HeapAlloc(GetProcessHeap(), 0, (SIZE_T) total * sizeof(WCHAR));
		if (*wpath == NULL) {
			(*env)->ReleaseStringChars(env, jpath, chars);
			return -1;
		}
		*heap = 1;
	}
	dst = 0;
	if (prefix == 4) {
		(*wpath)[0] = '\\';
		(*wpath)[1] = '\\';
		(*wpath)[2] = '?';
		(*wpath)[3] = '\\';
		dst = 4;
		src = 0;
	} else if (prefix == 8) {
		(*wpath)[0] = '\\';
		(*wpath)[1] = '\\';
		(*wpath)[2] = '?';
		(*wpath)[3] = '\\';
		(*wpath)[4] = 'U';
		(*wpath)[5] = 'N';
		(*wpath)[6] = 'C';
		(*wpath)[7] = '\\';
		dst = 8;
		src = 2;
	} else {
		src = 0;
	}
	while (src < len) {
		ch = (WCHAR) chars[src++];
		if (ch == '/') ch = '\\';
		(*wpath)[dst++] = ch;
	}
	(*wpath)[dst] = 0;
	(*env)->ReleaseStringChars(env, jpath, chars);
	return 0;
}

#endif
