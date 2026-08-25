# Third-party notices

## FilmKit

The Fujifilm device-property map (`D18C`–`D1A5`), the encoding tables in
`fuji/Codec.kt`, and the shape of the Fuji X Weekly text parser are derived from
[FilmKit](https://github.com/eggricesoy/filmkit), which reverse-engineered them
from Wireshark captures of X RAW Studio against an X100VI.

FilmKit is MIT-licensed, and its notice is reproduced here as that licence
requires:

```
MIT License

Copyright (c) 2026 eggricesoy

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Libraries

AndroidX, Jetpack Compose and Material 3 are Apache License 2.0. kotlinx
.serialization and the Kotlin standard library are Apache License 2.0. None of
them are vendored here; they are resolved by Gradle at build time and their
licences travel with them.

## Not affiliated with Fujifilm

Fujifilm, X-T30, X100 and the film simulation names are trademarks of FUJIFILM
Corporation. This project is independent, unofficial, and not endorsed by them.
It talks to the camera over the same standard PTP interface the camera already
exposes to Fujifilm's own desktop software.
