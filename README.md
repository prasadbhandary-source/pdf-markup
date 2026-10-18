# PDF Markup

A personal Android app for opening and annotating PDFs on a Pixel.

## What it does
- Opens any PDF: from the in-app picker (Files, Downloads, Google Drive…), or via
  **Open with → PDF Markup** / **Share → PDF Markup** from Files, Gmail, Chrome, Drive, etc.
- Password-protected PDFs: asks for the password.
- Damaged or unusual files: falls back to a second PDF engine if Android's built-in one can't read them.
- Smooth continuous scrolling, pinch-zoom up to 6×, double-tap to zoom; text stays sharp when zoomed.
- Tools: **Pen**, **Highlighter**, **Text notes**, **Eraser**, with colours and thickness.
  When drawing, one finger draws and two fingers scroll/zoom.
- Text notes can be edited (tap) and moved (drag) with the Text tool.
- Undo / redo.
- **Save** writes real PDF annotations back into the file, so they show up in Acrobat, Chrome,
  Drive, etc. If the app that shared the file only allows reading, it offers **Save a copy**.
- Annotations the app saved earlier stay editable when you reopen the file.
- Unsaved work is kept as a draft, so nothing is lost if Android closes the app.
- Recent files, remembering the page you were on. Share the annotated PDF from the ⋮ menu.

## Build
Push to `main` on GitHub. The **Build APK** workflow builds the app and publishes
`PdfMarkup.apk` as a GitHub Release (and as a workflow artifact).

## Install on the Pixel
Open the latest release on your phone, download `PdfMarkup.apk`, tap it and allow
"Install unknown apps" for your browser when asked. Later builds install over the top.
