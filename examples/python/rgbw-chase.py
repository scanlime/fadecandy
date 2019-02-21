#!/usr/bin/env python3

"""
(2019) Gabriel Wolf (gabriel.wolf@wolfzeit.com)
This example code is released into the public domain.

Problem:  We want to use SK6812 RGBW stripes with FadeCandy.
Solution: We fake WS2812 RGB stripes.
Infos:    - Total pixel count per channel is reduced from 64 to 48,
            due to one more (W) diode per pixel.
          - No support for FadeCandys white point feature anymore.
          - Pixel order must be set to 'grb'.
Usage:    sk_stripe = RGBW(sk_numLEDs)                   # create object
          sk_stripe.set_rgbw_pixel(sk_pixel, r, g, b, w) # set pixel
          pixels = sk_stripe.get_pixels()                # get values
          client.put_pixels(pixels)                      # pass to FadeCandy

          Internally RGBW pixels are mapped to 3-tuples of RGB pixels.
          Let's look at the first three pixels:

          R    G    B    W                     R    G    B
          .    .    .    .                     .    .    .
      1 - 0    1    2    3                     0    1    2
      2 - 4    5    6    7        become       3    4    5
      3 - 8    9    10   11                    6    7    8
                                               9    10   11

"""

import opc
import time
import numpy as np

sk_numLEDs = 144

client = opc.Client('localhost:7890')


class RGBW(object):
    def __init__(self, sk_pixel_count='48'):
        self.internal_pixel_count = int(sk_pixel_count / 3 * 4)
        self.absolute_diode_count = sk_pixel_count * 4
        self.absolute_diode_values = np.zeros(
            self.absolute_diode_count,
            dtype=int
        )

    def set_rgbw_pixel(self, sk_pixel, r, g, b, w):
        i = int(round((sk_pixel + (sk_pixel - 1) / 3), 0))  # RGB pixel index

        # get relative positions of diodes in internal 3-tuples
        g_pos = ((sk_pixel + 0) % 3)
        r_pos = ((sk_pixel + 1) % 3)
        b_pos = ((sk_pixel + 2) % 3)
        w_pos = ((sk_pixel + 3) % 3)

        # make them absolute positions
        r_pos = ((i + 1) * 3 + r_pos if (sk_pixel + 1) % 3 == 0 else i * 3 + r_pos)
        g_pos = i * 3 + g_pos
        b_pos = (i * 3 + b_pos if sk_pixel % 3 == 0 else (i + 1) * 3 + b_pos)
        w_pos = (i + 1) * 3 + w_pos

        self.absolute_diode_values[r_pos] = r
        self.absolute_diode_values[g_pos] = g
        self.absolute_diode_values[b_pos] = b
        self.absolute_diode_values[w_pos] = w

    def get_pixels(self):
        internal_pixels = []
        for i in range(self.internal_pixel_count):
            internal_pixel = []
            for j in range(3):
                internal_pixel.append(self.absolute_diode_values[i * 3 + j])

            internal_pixels.append(tuple(internal_pixel))

        return internal_pixels


# A simple test: walk over all pixels and loop over all diodes

pixel_loop = 0
diode_loop = 0

while True:
    sk_stripe = RGBW(sk_numLEDs)

    # select actual pixel and diode
    if diode_loop is 0:
        sk_stripe.set_rgbw_pixel(pixel_loop, 128, 0, 0, 0)
    elif diode_loop is 1:
        sk_stripe.set_rgbw_pixel(pixel_loop, 0, 128, 0, 0)
    elif diode_loop is 2:
        sk_stripe.set_rgbw_pixel(pixel_loop, 0, 0, 128, 0)
    elif diode_loop is 3:
        sk_stripe.set_rgbw_pixel(pixel_loop, 0, 0, 0, 128)

    client.put_pixels(sk_stripe.get_pixels())

    # continuous walk over all pixels
    if pixel_loop < sk_numLEDs - 1:
        pixel_loop += 1
    else:
        pixel_loop = 0

        # continuous walk over all diodes
        if diode_loop < 3:
            diode_loop += 1
        else:
            diode_loop = 0

    time.sleep(0.02)
