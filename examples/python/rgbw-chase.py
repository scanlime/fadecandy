#!/usr/bin/env python

"""
(2019) Gabriel Wolf (gabriel.wolf@wolfzeit.com)
This example code is released into the public domain.

Problem:  We want to use SK6812 RGBW stripes with FadeCandy.
Solution: We fake WS2812 RGB stripes.
Infos:    1. Total pixel count is reduced from 64 to 48
             per channel, due to one more (W) diode per pixel.
          2. No support for FadeCandys white point feature anymore.
Warning:  Pixel order must be set to 'grb' !


==== contract ==== sk2ws(sk_pixel, r, g, b, w)

     == precondition ==
     sk_pixel := int [0, 47]     --- index of a SK6812 RGBW pixel
     r, g, b, w := int [0, 255]  --- 8 bit color values

     == postcondition ==
     { r_pos : r,
       g_pos : g,
       b_pos : b,
       w_pos : w  }

     The function returns a dictionary, that maps every given color to an
     absolute position within 3-tuples of a supported RGB stripe. That way
     you can transparently work with pixels and individual colors.

     Let's look on the first three RGBW pixels:

     R    G    B    W                     R    G    B
     ·    ·    ·    ·                     ·    ·    ·
 1 > 0    1    2    3                     0    1    2
 2 > 4    5    6    7        become       3    4    5
 3 > 8    9    10   11                    6    7    8
     ↓    ↓    ↓    ↓                     9    10   11
                                          ↓    ↓    ↓
"""

import opc, time

numLEDs = 48
client = opc.Client('localhost:7890')


def sk2ws(sk_pixel, r, g, b, w):
    ws_pixel_start = int(round((sk_pixel + (sk_pixel - 1) / 3), 0))

    # get relative positions of colors in 3-tuples
    g_pos = ((sk_pixel + 0) % 3)
    r_pos = ((sk_pixel + 1) % 3)
    b_pos = ((sk_pixel + 2) % 3)
    w_pos = ((sk_pixel + 3) % 3)

    # make them absolute positions
    # ----- red -----
    if (sk_pixel + 1) % 3 == 0:
        r_pos = (ws_pixel_start + 1) * 3 + r_pos
    else:
        r_pos = ws_pixel_start * 3 + r_pos

    # ----- green -----
    g_pos = ws_pixel_start * 3 + g_pos

    # ----- blue -----
    if sk_pixel % 3 == 0:
        b_pos = ws_pixel_start * 3 + b_pos
    else:
        b_pos = (ws_pixel_start + 1) * 3 + b_pos

    # ----- white -----
    w_pos = (ws_pixel_start + 1) * 3 + w_pos

    return {r_pos: r, g_pos: g, b_pos: b, w_pos: w}


# A simple test: walk over all pixels and loop over all diodes

pixel_loop = 0
diode_loop = 0

while True:
    # select actual pixel and diode
    if diode_loop is 0:
        pixel_data = sk2ws(pixel_loop, 128, 0, 0, 0)
    elif diode_loop is 1:
        pixel_data = sk2ws(pixel_loop, 0, 128, 0, 0)
    elif diode_loop is 2:
        pixel_data = sk2ws(pixel_loop, 0, 0, 128, 0)
    elif diode_loop is 3:
        pixel_data = sk2ws(pixel_loop, 0, 0, 0, 128)

    pixels = []  # RGB map

    for i in range(64):
        pixel = []
        for j in range(3):
            pixel.append(pixel_data.get(i * 3 + j, 0))

        pixels.append(tuple(pixel))

    client.put_pixels(pixels)

    # continuous walk over all pixels
    if pixel_loop < 47:
        pixel_loop += 1
    else:
        pixel_loop = 0

        # continuous walk over all diodes
        if diode_loop < 3:
            diode_loop += 1
        else:
            diode_loop = 0

    time.sleep(0.02)
