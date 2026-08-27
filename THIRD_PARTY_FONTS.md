# Bundled fonts

Cicero ships three families, all under the SIL Open Font License 1.1, which
permits bundling in an application.

| Family        | Files                                                              | Role                                |
|---------------|--------------------------------------------------------------------|-------------------------------------|
| Cinzel        | `cinzel_regular.ttf`, `cinzel_bold.ttf`                            | wordmark and display sizes          |
| IBM Plex Sans | `ibm_plex_sans_{regular,medium,semibold}.ttf`                      | body, labels, chat                  |
| IBM Plex Mono | `ibm_plex_mono_regular.ttf`                                        | model ids, backends, timestamps     |

Retrieved from the Google Fonts static instances on `fonts.gstatic.com`, resolved
via the CSS2 API. They live in `app/src/main/res/font/`, whose filenames must stay
lowercase with underscores — `aapt` rejects anything else.

Full licence text: https://openfontlicense.org

The Plex Sans files are ~205KB each because they carry Latin-Ext, Cyrillic and
Greek. If APK size ever matters, subset them to Latin.
