# Pixoo Max DnD Fight App

## App

This repository contains the code to run an Android 13 app on a mobile phone
and connect it to a Divoom Pixoo Max display via Bluetooth. It is written
in Kotlin.

The app is designed to aid the user in a Dnd style round-based fight.

Functions:

- Connect to a Pixoo Max display via Bluetooth
- Select and order images from the phone
    - Each image presents a hero or an enemy
    - The order should reflect their order in a fight round (e.g. based on their initiative)
- In "Play" mode
    - A single image is shown on the Pixoo Max display (hero or enemy)
    - The user can click the "Next" button on the app to show the next image in the chosen order
    - The "fight round" is shown in the top left corner on the Pixoo Max display

## Prerequisites

To use the app, ensure that these prerequisites are met:

- Android 13+
- Pixoo Max Display is turned on
- Mobile phone has bluetooth enabled
- Pixoo max display and mobile phone are paired via bluetooth
- When the app starts for the first time, it needs to be given the permission to use bluetooth

## Images

The images will be scaled down to 32x32 pixels. Moreover,
the number of colors will be reduced to 256 if the image
uses more colors.
Since the "fight round" number in the top left corner
is always drawn in black, an image with a white background is recommended.

## Package structure

To send a single image with the `0x44` command via Bluetooth,
the packages are constructed as follows:

| Value | Bytes | Description                                                                    `                                                                                                 |
|-------|-------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 0x01  | 1     | Package start flag                                                                                                                                                               |
|       | 2     | Byte length of Command (1) + Payload + the Length itself (2), Little Endian                                                                                                      |
| 0x44  | 1     | Command to draw a single image                                                                                                                                                   |
| 0x00  | 1     | Payload (fixed)                                                                                                                                                                  |
| 0x0A  | 1     | Payload (fixed)                                                                                                                                                                  |
| 0x0A  | 1     | Payload (fixed)                                                                                                                                                                  |
| 0x04  | 1     | Payload (fixed)                                                                                                                                                                  |
| 0xAA  | 1     | Payload (fixed)                                                                                                                                                                  |
|       | 2     | Payload, 8 + number of bytes to encode color palette and image, Little Endian                                                                                                    |
| 0x00  | 1     | Payload (fixed)                                                                                                                                                                  |
| 0x00  | 1     | Payload (fixed)                                                                                                                                                                  |
| 0x03  | 1     | Payload (fixed)                                                                                                                                                                  |
|       | 2     | Payload, count of colors in palette, Little Endian                                                                                                                               |
|       |       | Payload, Color palette as RGB888, i.e. each color is encoded by 3 bytes (RGB)                                                                                                    |
|       |       | Payload, actual image where each pixel value is encoded as a reference to the color palette; every Pixel is encoded as densely as possible, i.e. a pixel can be less than 1 byte |
|       | 2     | Checksum as the sum of the Length, Command, and Payload, Little Endian                                                                                                           |
| 0x02  | 1     | Package end flag                                                                                                                                                                 |

## References

These two pages were extremely helpful in determining the overall package
structure when sending images to the Pixoo Max (0x44 command).

- [Protocol description](https://docin.divoom-gz.com/web/#/5/146)
- [0x44 command](https://docin.divoom-gz.com/web/#/5/289)

But my actual implementation is mostly based on this other Github repository:

- [pixoo-awesome by HoroTW](https://github.com/HoroTW/pixoo-awesome/blob/main/modules/pixoo_client.py)