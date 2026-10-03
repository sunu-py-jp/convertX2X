# Markdown PDF 変換サンプル

日本語の本文と **太字**、*斜体*、`インラインコード` を含みます。
[convertX2X](https://github.com/sunu-py-jp/convertX2X) のMarkdown変換です。

## 月次の集計

| 項目 | 4月 | 5月 |
| :--- | ---: | ---: |
| 受付件数 | 120 | 180 |
| 完了件数 | 108 | 175 |
| 補足 | 通常運用 | 手順の改善を実施 |

### 確認事項

- 表や文章はPDF内で検索できます。
- 画像はdata URI、またはZIP内の相対パスで指定できます。
  - 日本語フォントを同梱しています。

> 表が長くなった場合も、セルの文章をページをまたいで残します。

![件数の比較（サンプル画像）](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAWgAAABICAIAAABUXPgAAAABgUlEQVR4nO3coU0GAAyEUXZBMxGaRZiJzUgQKCymyX+Ya8NL3gAVl0/26fPrGyDyVL8AOEc4gJhwADHhAGLCAcSEA4gJBxATDiAmHEDsoXA8v7/xD9XXyVrCwai+TtYSDkb1dbKWcDCqr5O1hINRfZ2sJRyM6utkLeFgVF8nawkHo/o6WUs4GNXXyVrCwai+TtYSDkb1dbKWcDCqr5O1hINRfZ2sJRyM6utkLeFgVF8nawkHo/o6WUs4GNXXyVrCwai+TtbyOhCICQcQEw4gJhxATDiAmHAAMeEAYsIBxIQDiAkHEBMOICYcQEw4gJhwADHhAGLCAcQeCsfL6wdQUW+EcMA99UYIB9xTb4RwwD31RggH3FNvhHDAPfVGCAfcU2+EcMA99UYIB9xTb4RwwD31RggH3FNvhHDAPfVGCAfcU2+EcMA99UYIB9xTb4RwwD31RggH3FNvhHDAPfVG/D0cAL8JBxATDiAmHEBMOICYcAAx4QBiwgHEhAOICQcQ+wHCauMKiPPiSAAAAABJRU5ErkJggg==)

<!-- pagebreak -->

## 実行例

1. Markdownをアップロードします。
2. PDFをダウンロードします。

```python
for month in ["April", "May"]:
    print(month)
```

---

このページは明示的な改ページで開始しています。
