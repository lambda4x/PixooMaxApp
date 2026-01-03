# Pixoo Max App

## Aim

This repository contains the code to run an Android 13 app on a mobile phone
and connect it to a Divoom Pixoo Max display via Bluetooth. It is written
in Kotlin.

The app lets a user select several images from their phone, order them,
and send them to the Pixoo Max display. The next image in the sequence is only
shown when a "Next" button is clicked on the app.

This behavior differs from the
official Divoom app for the Pixoo Max in that the official app only
lets the user configure the time for which an image is shown. After that,
the app automatically sends the next image in the sequence.

## Why another Pixoo Max repository

While there are already several repositories with code to connect to a Pixoo Max,
I was unable to find one that I could integrate into an Android app.
Therefore, I had to write my own.

## Prerequisites

To use the app, ensure that these prerequisites are met:

- Pixoo Max Display is turned on
- Mobile phone has bluetooth enabled
- Pixoo max display and mobile phone are paired via bluetooth
- When the app starts for the first time, it needs to be given the permission to use bluetooth

## References

These two pages were extremely helpful in determining the overall package
structure when sending images to the Pixoo Max (0x44 command).

- [Protocol description](https://docin.divoom-gz.com/web/#/5/146)
- [0x44 command](https://docin.divoom-gz.com/web/#/5/289)

But my actual implementation is mostly based on this other Github repository:

- [pixoo-awesome by HoroTW](https://github.com/HoroTW/pixoo-awesome/blob/main/modules/pixoo_client.py)