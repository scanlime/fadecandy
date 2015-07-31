###########################################################################
# Fadecandy Server

TARGET := fcserver

CPP_FILES += \
	src/main.cpp \
	src/tcpnetserver.cpp \
	src/usbdevice.cpp \
	src/fcdevice.cpp \
	src/enttecdmxdevice.cpp \
	src/fcserver.cpp \
	src/version.cpp \
	src/tinythread.cpp \
	src/httpdocs.cpp

INCLUDES += -Isrc
CLEAN_FILES += src/*.d src/*.o src/httpdocs.cpp
CPPFLAGS += -Wno-strict-aliasing

###########################################################################
# System Support

SYS := $(shell $(CXX) -dumpmachine)

ifneq (, $(findstring linux, $(SYS)))
UNAME := Linux
endif
ifneq (, $(findstring mingw, $(SYS)))
UNAME := MINGW32
endif
ifneq (, $(findstring darwin, $(SYS)))
UNAME := Darwin
endif

MINGW := $(findstring MINGW32, $(UNAME))
LIBS += -lstdc++ -lm
VERSION := $(shell git describe --match "fcserver-*")
CXXFLAGS += -DFCSERVER_VERSION=$(VERSION)

ifeq ($(UNAME), Darwin)
	# Mac OS X (32-bit build)
	LDFLAGS += -m32
	CPPFLAGS += -m32 -DHAVE_POLL_H

	ifeq ("$(shell which llvm-gcc)", "")
		# We want to support all the way back to OS 10.6 (Snow Leopard), which used gcc
		# instead of llvm. Omit some flags that this old gcc doesn't handle.
	else
		# Assume it's a new enough Mac OS version
		CPPFLAGS += -Wno-tautological-constant-out-of-range-compare
		CXXFLAGS += -std=gnu++0x
	endif
else
	# Everyone except ancient versions of gcc on Mac OS likes this flag...
	CXXFLAGS += -std=gnu++0x
endif

ifneq ("$(MINGW)", "")
	# Windows
	TARGET := $(TARGET).exe
	CPPFLAGS += -D_WIN32_WINNT=0x0501

	# Static build makes it portable but big, UPX packer decreases size a lot.
	LDFLAGS += -static
	PACK_CMD := upx\upx391w.exe $(TARGET)
endif

ifneq ("$(DEBUG)", "")
	# Debug build
	TARGET := debug-$(TARGET)
	CPPFLAGS += -g -DDEBUG -DENABLE_LOGGING
	PACK_CMD :=
else
	# Optimized build	
	STRIP_CMD := strip $(TARGET)
	CPPFLAGS += -Os -DNDEBUG
	LDFLAGS += -Os
endif

###########################################################################
# Built-in rapidjson

INCLUDES += -I.

###########################################################################
# Built-in libwebsockets

C_FILES += \
	libwebsockets/lib/handshake.c \
	libwebsockets/lib/libwebsockets.c \
	libwebsockets/lib/parsers.c \
	libwebsockets/lib/server-handshake.c \
	libwebsockets/lib/server.c \
	libwebsockets/lib/output.c \
	libwebsockets/lib/sha-1.c \
	libwebsockets/lib/base64-decode.c

# For lws_get_library_version(), which we don't use.
CPPFLAGS += -DLWS_LIBRARY_VERSION= -DLWS_BUILD_HASH=

# Disable a bunch of features
CPPFLAGS += -DLWS_NO_EXTENSIONS -DLWS_NO_CLIENT -DLWS_NO_WSAPOLL

INCLUDES += -Ilibwebsockets/lib
CLEAN_FILES += libwebsockets/lib/*.d libwebsockets/lib/*.o

ifneq ("$(MINGW)", "")
	# Windows
	INCLUDES += -Ilibwebsockets/win32port/win32helpers
else
	# This is redundant on Windows, but we want it on other platforms
	CPPFLAGS += -DLWS_NO_DAEMONIZE
endif

###########################################################################
# Built-in libusbx

C_FILES += \
	libusbx/libusb/core.c \
	libusbx/libusb/descriptor.c \
	libusbx/libusb/hotplug.c \
	libusbx/libusb/io.c \
	libusbx/libusb/strerror.c \
	libusbx/libusb/sync.c

ifeq ($(UNAME), Darwin)
	# Mac OS X

	C_FILES += \
		libusbx/libusb/os/darwin_usb.c \
		libusbx/libusb/os/poll_posix.c \
		libusbx/libusb/os/threads_posix.c

	LIBS += -framework CoreFoundation -framework IOKit -lobjc
	CPPFLAGS += -DOS_DARWIN -DTHREADS_POSIX -DPOLL_NFDS_TYPE=nfds_t \
		-DLIBUSB_CALL= -DDEFAULT_VISIBILITY= -DHAVE_GETTIMEOFDAY
endif

ifeq ($(UNAME), Linux)
	# Linux

	C_FILES += \
		libusbx/libusb/os/linux_usbfs.c \
		libusbx/libusb/os/linux_netlink.c \
		libusbx/libusb/os/poll_posix.c \
		libusbx/libusb/os/threads_posix.c

	LIBS += -lpthread -lrt
	CPPFLAGS += -DOS_LINUX -DTHREADS_POSIX -DPOLL_NFDS_TYPE=nfds_t \
		-DLIBUSB_CALL= -DDEFAULT_VISIBILITY= -DHAVE_GETTIMEOFDAY -DHAVE_POLL_H \
		-DHAVE_ASM_TYPES_H -DHAVE_SYS_SOCKET_H -DHAVE_LINUX_NETLINK_H -DHAVE_LINUX_FILTER_H
endif

ifneq ("$(MINGW)", "")
	# Windows

	C_FILES += \
		libusbx/libusb/os/windows_usb.c \
		libusbx/libusb/os/poll_windows.c \
		libusbx/libusb/os/threads_windows.c

	LIBS += -lws2_32
	CPPFLAGS += -DOS_WINDOWS -DPOLL_NFDS_TYPE=int -DDEFAULT_VISIBILITY= -DHAVE_GETTIMEOFDAY
endif

INCLUDES += -Ilibusbx/libusb
CLEAN_FILES += \
	libusbx/libusb/*.d libusbx/libusb/*.o \
	libusbx/libusb/os/*.d libusbx/libusb/os/*.o

###########################################################################
# Build Rules

# Compiler options for C and C++
CPPFLAGS += -MMD $(INCLUDES)

# Compiler options for C++ only
CXXFLAGS += -felide-constructors -fno-exceptions -fno-rtti

OBJS := $(CPP_FILES:.cpp=.o) $(C_FILES:.c=.o)

print-%: ; @echo $* = $($*)
all: print-SYS $(TARGET)

# FIXME: A race condition between objects regeneration and their source mtime in make ? 
$(TARGET): $(SUBMODULES_TARGETS) $(OBJS)
	$(CXX) $(LDFLAGS) -o $@ $(OBJS) $(LIBS)
	$(STRIP_CMD)
	$(PACK_CMD)
	rm -f src/version.o

-include $(OBJS:.o=.d)

src/httpdocs.cpp: http/* http/js/* http/css/*
	(cd http; python manifest.py) > $@

clean:
	rm -f $(CLEAN_FILES) $(TARGET)

# Git submodules handling. TODO: Add submodules cleaning to clean target 
SUBMODULES_TARGETS:=$(shell git config -f ../.gitmodules --get-regexp submodule'.*\.'path|cut -d ' ' -f 2|cut -d '/' -f 2)
$(SUBMODULES_TARGETS):
	cd .. && git submodule update --init -- server/$@ && cd server/$@ && git checkout -f HEAD && git clean -dfx
submodules: $(SUBMODULES_TARGETS)

.PHONY: submodules $(SUBMODULES_TARGETS) clean all
