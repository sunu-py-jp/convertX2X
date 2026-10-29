# Noto Sans JP provenance

The upstream is the official [Google Fonts repository](https://github.com/google/fonts/tree/66a36c8c94b1a5d992ee4e7f392fccfe4945767c/ofl/notosansjp), pinned to commit `66a36c8c94b1a5d992ee4e7f392fccfe4945767c`. `METADATA.pb` is an unchanged copy from that commit. It identifies the family as **Noto Sans JP** and records the underlying noto-cjk source commit `523d033d6cb47f4a80c58a35753646f5c3608a78`.

The downloaded `NotoSansJP[wght].ttf` is version 2.004-H2, with the weight axis 100–900. The application ships full-glyph static TrueType instances at weight 400 (Regular) and 700 (Bold), generated using FontTools 4.68.0. Instantiation resolves the variation axis, updates the style/name tables, and preserves the upstream timestamp for reproducibility. It does not subset characters or change outlines beyond evaluating the selected weight.

The original SIL Open Font License 1.1 and Adobe copyright are in `assets/fonts/OFL.txt` at the application root and remain in the fonts' name tables. The upstream reserved font name is `Source`; the generated family remains `Noto Sans JP`. The static instances remain licensed under OFL-1.1. Their generation is disclosed here rather than described as an unmodified copy of the upstream variable font.

`provenance.json` records immutable source URLs, SHA-256 values, the generator version, and the names/weights/glyph counts verified in each generated file. Packaging verifies the shipped font and OFL hashes against that record.

## Reproduction

Download [the pinned variable TTF](https://raw.githubusercontent.com/google/fonts/66a36c8c94b1a5d992ee4e7f392fccfe4945767c/ofl/notosansjp/NotoSansJP%5Bwght%5D.ttf) into a temporary location. In a separate maintenance environment with Python and `fonttools==4.68.0`, run:

```shell
python instantiate.py downloaded-variable.ttf output-fonts
```

The script refuses another input hash or FontTools version. Compare the two generated hashes with `provenance.json` before replacing shipped files. The variable source is not needed during conversion and is not included in the deployment package. Python and FontTools are not application runtime dependencies.

Generated PowerPoint files refer to the family name but do not embed these font files. Runtime font selection, font licensing and PPTX font embedding are separate concerns.
