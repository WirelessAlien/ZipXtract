# -Wall
# -DRAR_NOCRYPT
#-DNOVOLUME 
#-UMBFUNCTIONS  -fvisibility=hidden  -UUNICODE_SUPPORTED -UMBFUNCTIONS
LOCAL_PATH :=$(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := unrar
LOCAL_CPPFLAGS += -DLITTLE_ENDIAN
LOCAL_CFLAGS +=  -DANDROID -DRARDLL   -DLITTLE_ENDIAN -DUNIX_TIME_NS\
  #-D_FILE_OFFSET_BITS=64 -D_LARGEFILE_SOURCE
#-fshort-wchar
# UnRar lib directory name
UNRAR_SRC := unrar-6.1.7
LOCAL_CPP_FEATURES := exceptions

LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)
LOCAL_SRC_FILES +=${UNRAR_SRC}/archive.cpp \
                ${UNRAR_SRC}/arcread.cpp \
                ${UNRAR_SRC}/blake2s.cpp \
                ${UNRAR_SRC}/cmddata.cpp \
                ${UNRAR_SRC}/consio.cpp \
                ${UNRAR_SRC}/crc.cpp \
                ${UNRAR_SRC}/crypt.cpp \
                ${UNRAR_SRC}/dll.cpp \
                ${UNRAR_SRC}/encname.cpp \
                ${UNRAR_SRC}/errhnd.cpp \
                ${UNRAR_SRC}/extinfo.cpp \
                ${UNRAR_SRC}/extract.cpp \
                ${UNRAR_SRC}/filcreat.cpp \
                ${UNRAR_SRC}/file.cpp \
                ${UNRAR_SRC}/filefn.cpp \
                ${UNRAR_SRC}/filestr.cpp \
                ${UNRAR_SRC}/find.cpp \
                ${UNRAR_SRC}/getbits.cpp \
                ${UNRAR_SRC}/global.cpp \
                ${UNRAR_SRC}/hash.cpp \
                ${UNRAR_SRC}/headers.cpp \
                ${UNRAR_SRC}/list.cpp \
                ${UNRAR_SRC}/match.cpp \
                ${UNRAR_SRC}/options.cpp \
                ${UNRAR_SRC}/pathfn.cpp \
                ${UNRAR_SRC}/qopen.cpp \
                ${UNRAR_SRC}/rar.cpp \
                ${UNRAR_SRC}/rarvm.cpp \
                ${UNRAR_SRC}/rawread.cpp \
                ${UNRAR_SRC}/rdwrfn.cpp \
                ${UNRAR_SRC}/resource.cpp \
                ${UNRAR_SRC}/rijndael.cpp \
                ${UNRAR_SRC}/scantree.cpp \
                ${UNRAR_SRC}/secpassword.cpp \
                ${UNRAR_SRC}/sha1.cpp \
                ${UNRAR_SRC}/sha256.cpp \
                ${UNRAR_SRC}/smallfn.cpp \
                ${UNRAR_SRC}/strfn.cpp \
                ${UNRAR_SRC}/strlist.cpp \
                ${UNRAR_SRC}/system.cpp \
                ${UNRAR_SRC}/timefn.cpp \
                ${UNRAR_SRC}/ulinks.cpp \
                ${UNRAR_SRC}/ui.cpp \
                ${UNRAR_SRC}/unicode.cpp \
                ${UNRAR_SRC}/unpack.cpp \
                ${UNRAR_SRC}/volume.cpp


include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := unrardyn
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)
#LOCAL_CFLAGS += -fshort-wchar
LOCAL_CFLAGS +=  -DRARDLL   -DLITTLE_ENDIAN \
#-D_FILE_OFFSET_BITS=64 -D_LARGEFILE_SOURCE
LOCAL_SRC_FILES = unrardyn.cpp

LOCAL_LDLIBS := -llog

LOCAL_STATIC_LIBRARIES := unrar

LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS := -Wl,--as-needed

include $(BUILD_SHARED_LIBRARY)



