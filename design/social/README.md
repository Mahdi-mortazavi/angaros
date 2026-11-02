# Social preview

`social-preview.html` is the source of `docs/assets/social-preview.png` (1280 × 640, used as the
GitHub social preview, the README banner and the Open Graph image). Render it with any Chromium:

```sh
msedge --headless --disable-gpu --hide-scrollbars --window-size=1280,640 \
  --screenshot=docs/assets/social-preview.png design/social/social-preview.html
```

(`chrome` / `chromium` take the same flags.) Upload the PNG in the repository's
Settings → General → Social preview.
