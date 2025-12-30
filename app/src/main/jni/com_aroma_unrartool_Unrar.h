/*
 * Copyright (c) 2019.
 * Mahmoud Galal
 *
 */
#include <jni.h>
/* Header for class com_aroma_unrartool_Unrar */

#ifndef _Included_com_aroma_unrartool_Unrar
#define _Included_com_aroma_unrartool_Unrar
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     com_aroma_unrartool_Unrar
 * Method:    RarOpenArchive
 * Signature: (Ljava/lang/String;)I
 */
JNIEXPORT jint JNICALL Java_com_aroma_unrartool_Unrar_RarOpenArchive
  (JNIEnv *, jobject, jstring, jstring);


JNIEXPORT jint JNICALL Java_com_aroma_unrartool_Unrar_RarGetArchiveItems
  (JNIEnv *, jobject, jstring fname);

/*
 * Class:     com_aroma_unrartool_Unrar
 * Method:    RarCloseArchive
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL Java_com_aroma_unrartool_Unrar_RarCloseArchive
  (JNIEnv *, jobject, jint);

/*
 * Class:     com_aroma_unrartool_Unrar
 * Method:    RarProcessArchive
 * Signature: (ILjava/lang/String;)I
 */
JNIEXPORT jint JNICALL Java_com_aroma_unrartool_Unrar_RarProcessArchive
  (JNIEnv *, jobject, jint, jstring);

JNIEXPORT void JNICALL Java_com_aroma_unrartool_Unrar_init
  (JNIEnv *, jclass);

#ifdef __cplusplus
}
#endif
#endif
