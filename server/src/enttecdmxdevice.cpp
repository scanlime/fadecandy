/*
 * Fadecandy driver for the Enttec DMX USB Pro.
 * 
 * Copyright (c) 2013 Micah Elizabeth Scott
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

#include "enttecdmxdevice.h"
#include "rapidjson/stringbuffer.h"
#include "rapidjson/writer.h"
#include "opc.h"
#include <sstream>
#include <iostream>


EnttecDMXDevice::Transfer::Transfer(EnttecDMXDevice *device, void *buffer, int length)
    : transfer(libusb_alloc_transfer(0)), finished(false)
{
    libusb_fill_bulk_transfer(transfer, device->mHandle,
        OUT_ENDPOINT, (uint8_t*) buffer, length, EnttecDMXDevice::completeTransfer, this, 2000);
}

EnttecDMXDevice::Transfer::~Transfer()
{
    libusb_free_transfer(transfer);
}

EnttecDMXDevice::EnttecDMXDevice(libusb_device *device, bool verbose)
    : USBDevice(device, "enttec", verbose),
      mFoundEnttecStrings(false),
      mConfigMap(0)
{
    mSerialBuffer[0] = '\0';
    mSerialString = mSerialBuffer;

    // Initialize a minimal valid DMX packet
    memset(&mChannelBuffer, 0, sizeof mChannelBuffer);
    mChannelBuffer.start = START_OF_MESSAGE;
    mChannelBuffer.label = SEND_DMX_PACKET;
    mChannelBuffer.data[0] = START_CODE;
    setChannel(1, 0);
}

EnttecDMXDevice::~EnttecDMXDevice()
{
    /*
     * If we have pending transfers, cancel them.
     * The Transfer objects themselves will be freed once libusb completes them.
     */

    for (std::set<Transfer*>::iterator i = mPending.begin(), e = mPending.end(); i != e; ++i) {
        Transfer *fct = *i;
        libusb_cancel_transfer(fct->transfer);
    }
}

bool EnttecDMXDevice::probe(libusb_device *device)
{
    /*
     * Prior to opening the device, all we can do is look for an FT245 device.
     * We'll take a closer look in probeAfterOpening(), once we can see the
     * string descriptors.
     */

    libusb_device_descriptor dd;

    if (libusb_get_device_descriptor(device, &dd) < 0) {
        // Can't access descriptor?
        return false;
    }

    // FTDI FT245
    return dd.idVendor == 0x0403 && dd.idProduct == 0x6001;
}

int EnttecDMXDevice::open()
{
    libusb_device_descriptor dd;
    int r = libusb_get_device_descriptor(mDevice, &dd);
    if (r < 0) {
        return r;
    }

    r = libusb_open(mDevice, &mHandle);
    if (r < 0) {
        return r;
    }

    /*
     * Match the manufacturer and product strings! This is the least intrusive way to
     * determine that the attached device is in fact an Enttec DMX USB Pro, since it doesn't
     * have a unique vendor/product ID.
     */

    if (dd.iManufacturer && dd.iProduct && dd.iSerialNumber) {
        char manufacturer[256];
        char product[256];

        r = libusb_get_string_descriptor_ascii(mHandle, dd.iManufacturer, (uint8_t*)manufacturer, sizeof manufacturer);
        if (r < 0) {
            return r;
        }
        r = libusb_get_string_descriptor_ascii(mHandle, dd.iProduct, (uint8_t*)product, sizeof product);
        if (r < 0) {
            return r;
        }

        mFoundEnttecStrings = !strcmp(manufacturer, "ENTTEC") && !strcmp(product, "DMX USB PRO");
    }

    /*
     * Only go further if we have in fact found evidence that this is the right device.
     */

    if (mFoundEnttecStrings) {

        // Only relevant on linux; try to detach the FTDI driver.
        libusb_detach_kernel_driver(mHandle, 0);

        r = libusb_claim_interface(mHandle, 0);
        if (r < 0) {
            return r;
        }

        r = libusb_get_string_descriptor_ascii(mHandle, dd.iSerialNumber,
            (uint8_t*)mSerialBuffer, sizeof mSerialBuffer);
        if (r < 0) {
            return r;
        }
    }

    return 0;
}

bool EnttecDMXDevice::probeAfterOpening()
{
    // By default, any device is supported by the time we get to opening it.
    return mFoundEnttecStrings;
}

void EnttecDMXDevice::loadConfiguration(const Value &config)
{
    mConfigMap = findConfigMap(config);
}

std::string EnttecDMXDevice::getName()
{
    std::ostringstream s;
    s << "Enttec DMX USB Pro";
    if (mSerialString[0]) {
        s << " (Serial# " << mSerialString << ")";
    }
    return s.str();
}

void EnttecDMXDevice::setChannel(unsigned n, uint8_t value)
{
    if (n >= 1 && n <= 512) {
        unsigned len = std::max<unsigned>(mChannelBuffer.length, n + 1);
        mChannelBuffer.length = len;
        mChannelBuffer.data[n] = value;
        mChannelBuffer.data[len] = END_OF_MESSAGE;
    }
}

void EnttecDMXDevice::submitTransfer(Transfer *fct)
{
    /*
     * Submit a new USB transfer. The Transfer object is guaranteed to be freed eventually.
     * On error, it's freed right away.
     */

    int r = libusb_submit_transfer(fct->transfer);

    if (r < 0) {
        if (mVerbose && r != LIBUSB_ERROR_PIPE) {
            std::clog << "Error submitting USB transfer: " << libusb_strerror(libusb_error(r)) << "\n";
        }
        delete fct;
    } else {
        mPending.insert(fct);
    }
}

void EnttecDMXDevice::completeTransfer(struct libusb_transfer *transfer)
{
    EnttecDMXDevice::Transfer *fct = static_cast<EnttecDMXDevice::Transfer*>(transfer->user_data);
    fct->finished = true;
}

void EnttecDMXDevice::flush()
{
    // Erase any finished transfers

    std::set<Transfer*>::iterator current = mPending.begin();
    while (current != mPending.end()) {
        std::set<Transfer*>::iterator next = current;
        next++;

        Transfer *fct = *current;
        if (fct->finished) {
            mPending.erase(current);
            delete fct;
        }

        current = next;
    }
}

void EnttecDMXDevice::writeDMXPacket()
{
    /*
     * Asynchronously write an FTDI packet containing an Enttec packet containing
     * our set of DMX channels.
     *
     * XXX: We should probably throttle this so that we don't send DMX messages
     *      faster than the Enttec device can keep up!
     */

    submitTransfer(new Transfer(this, &mChannelBuffer, mChannelBuffer.length + 5));
}

void EnttecDMXDevice::writeMessage(const OPC::Message &msg)
{
    /*
     * Dispatch an incoming OPC command
     */

    switch (msg.command) {

        case OPC::SetPixelColors:
            opcSetPixelColors(msg);
            writeDMXPacket();
            return;

        case OPC::SystemExclusive:
            // No relevant SysEx for this device
            return;
    }

    if (mVerbose) {
        std::clog << "Unsupported OPC command: " << unsigned(msg.command) << "\n";
    }
}

void EnttecDMXDevice::opcSetPixelColors(const OPC::Message &msg)
{
    /*
     * Parse through our device's mapping, and store any relevant portions of 'msg'
     * in the framebuffer.
     */

    if (!mConfigMap) {
        // No mapping defined yet. This device is inactive.
        return;
    }

    const Value &map = *mConfigMap;
    for (unsigned i = 0, e = map.Size(); i != e; i++) {
        opcMapPixelColors(msg, map[i]);
    }
}

void EnttecDMXDevice::opcMapPixelColors(const OPC::Message &msg, const Value &inst)
{
    /*
     * Parse one JSON mapping instruction, and copy any relevant parts of 'msg'
     * into our framebuffer. This looks for any mapping instructions that we
     * recognize:
     *
     *   [ OPC Channel, OPC Pixel, Pixel Color, DMX Channel ]
     */

    unsigned msgPixelCount = msg.length() / 3;

    if (inst.IsArray() && inst.Size() == 4) {
        // Map a range from an OPC channel to our framebuffer

        const Value &vChannel = inst[0u];
        const Value &vPixelIndex = inst[1];
        const Value &vPixelColor = inst[2];
        const Value &vDMXChannel = inst[3];

        if (vChannel.IsUint() && vPixelIndex.IsUint() && vPixelColor.IsString() && vDMXChannel.IsUint()) {
            unsigned channel = vChannel.GetUint();
            unsigned pixelIndex = vPixelIndex.GetUint();
            const char *pixelColor = vPixelColor.GetString();
            unsigned dmxChannel = vDMXChannel.GetUint();

            if (channel != msg.channel || pixelIndex >= msgPixelCount) {
                return;
            }

            const uint8_t *pixel = msg.data + (pixelIndex * 3);
            uint8_t value;

            if (OPC::pickColorChannel(value, pixelColor[0], pixel)) {
                setChannel(dmxChannel, value);
                return;
            }
        }
    }

    if (inst.IsArray() && inst.Size() == 2) {
        // Constant value

        const Value &vValue = inst[0u];
        const Value &vDMXChannel = inst[1];

        if (vValue.IsUint() && vDMXChannel.IsUint()) {
            unsigned value = vValue.GetUint();
            unsigned dmxChannel = vDMXChannel.GetUint();

            setChannel(dmxChannel, value);
            return;
        }
    }

    // Still haven't found a match?
    if (mVerbose) {
        rapidjson::GenericStringBuffer<rapidjson::UTF8<> > buffer;
        rapidjson::Writer<rapidjson::GenericStringBuffer<rapidjson::UTF8<> > > writer(buffer);
        inst.Accept(writer);
        std::clog << "Unsupported JSON mapping instruction: " << buffer.GetString() << "\n";
    }
}
