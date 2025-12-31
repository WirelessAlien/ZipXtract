# -Wall
# -DRAR_NOCRYPT
#-DNOVOLUME 
#-UMBFUNCTIONS  -fvisibility=hidden  -UUNICODE_SUPPORTED -UMBFUNCTIONS
LOCAL_PATH :=$(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := unrar
LOCAL_CPPFLAGS += -DLITTLE_ENDIAN
LOCAL_CFLAGS +=  -DANDROID -DRARDLL   -DLITTLE_ENDIAN \
  #-D_FILE_OFFSET_BITS=64 -D_LARGEFILE_SOURCE
#-fshort-wchar

LOCAL_CPP_FEATURES := exceptions

LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)
LOCAL_SRC_FILES +=unrar-6.1.7/archive.cpp \
                unrar-6.1.7/arcread.cpp \
                unrar-6.1.7/blake2s.cpp \
                unrar-6.1.7/cmddata.cpp \
                unrar-6.1.7/consio.cpp \
                unrar-6.1.7/crc.cpp \
                unrar-6.1.7/crypt.cpp \
                unrar-6.1.7/dll.cpp \
                unrar-6.1.7/encname.cpp \
                unrar-6.1.7/errhnd.cpp \
                unrar-6.1.7/extinfo.cpp \
                unrar-6.1.7/extract.cpp \
                unrar-6.1.7/filcreat.cpp \
                unrar-6.1.7/file.cpp \
                unrar-6.1.7/filefn.cpp \
                unrar-6.1.7/filestr.cpp \
                unrar-6.1.7/find.cpp \
                unrar-6.1.7/getbits.cpp \
                unrar-6.1.7/global.cpp \
                unrar-6.1.7/hash.cpp \
                unrar-6.1.7/headers.cpp \
                unrar-6.1.7/list.cpp \
                unrar-6.1.7/match.cpp \
                unrar-6.1.7/options.cpp \
                unrar-6.1.7/pathfn.cpp \
                unrar-6.1.7/qopen.cpp \
                unrar-6.1.7/rar.cpp \
                unrar-6.1.7/rarvm.cpp \
                unrar-6.1.7/rawread.cpp \
                unrar-6.1.7/rdwrfn.cpp \
                unrar-6.1.7/resource.cpp \
                unrar-6.1.7/rijndael.cpp \
                unrar-6.1.7/scantree.cpp \
                unrar-6.1.7/secpassword.cpp \
                unrar-6.1.7/sha1.cpp \
                unrar-6.1.7/sha256.cpp \
                unrar-6.1.7/smallfn.cpp \
                unrar-6.1.7/strfn.cpp \
                unrar-6.1.7/strlist.cpp \
                unrar-6.1.7/system.cpp \
                unrar-6.1.7/timefn.cpp \
                unrar-6.1.7/ulinks.cpp \
                unrar-6.1.7/ui.cpp \
                unrar-6.1.7/unicode.cpp \
                unrar-6.1.7/unpack.cpp \
                unrar-6.1.7/volume.cpp

LOCAL_LDLIBS := -llog

LOCAL_LDFLAGS := -Wl,--as-needed
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
include $(BUILD_SHARED_LIBRARY)


