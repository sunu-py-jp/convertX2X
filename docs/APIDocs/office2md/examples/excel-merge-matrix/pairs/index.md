# Excel結合・見出しのスクリーンショットと変換後Markdown

各スクリーンショットは入力ブックのセル・直接塗り・結合を比較ページ上でHTML表示したものです。Excelアプリ画面ではありません。Markdownは実際の変換結果 `output/document.md` から、対応するシートの文字列を変えずに切り出しています。

| ID | 入力パターン・シート | スクリーンショット | 変換後Markdown |
| --- | --- | --- | --- |
| C01 | 色付き1行ヘッダー・結合なし<br><code>01_単行_色あり_結合なし</code> | [PNG](../screenshots/C01.png) | [Markdown](C01/document.md) |
| C02 | 色付き2行ヘッダー・結合なし<br><code>02_複行_色あり_結合なし</code> | [PNG](../screenshots/C02.png) | [Markdown](C02/document.md) |
| C03 | 色付き3行ヘッダー<br><code>03_複行3段_色あり</code> | [PNG](../screenshots/C03.png) | [Markdown](C03/document.md) |
| C04 | 色なし・結合なし<br><code>04_全行_色なし</code> | [PNG](../screenshots/C04.png) | [Markdown](C04/document.md) |
| C05 | 先頭無色・次行だけ色付き<br><code>05_先頭無色_次行色あり</code> | [PNG](../screenshots/C05.png) | [Markdown](C05/document.md) |
| C06 | 色付き→無色→色付き<br><code>06_色_無色_色</code> | [PNG](../screenshots/C06.png) | [Markdown](C06/document.md) |
| C07 | 先頭の1セルだけ色付き<br><code>07_先頭1セルだけ色あり</code> | [PNG](../screenshots/C07.png) | [Markdown](C07/document.md) |
| C08 | 1行ヘッダーだけ横結合<br><code>08_単行_上だけ横結合</code> | [PNG](../screenshots/C08.png) | [Markdown](C08/document.md) |
| C09 | 1行ヘッダーが全列横結合<br><code>09_単行_全幅横結合</code> | [PNG](../screenshots/C09.png) | [Markdown](C09/document.md) |
| C10 | 2行ヘッダー・上段だけ横結合<br><code>10_複行_上段横結合</code> | [PNG](../screenshots/C10.png) | [Markdown](C10/document.md) |
| C11 | 2行ヘッダー・縦結合と横結合<br><code>11_複行_縦横結合</code> | [PNG](../screenshots/C11.png) | [Markdown](C11/document.md) |
| C12 | 全行で同じ幅の横結合・1行ヘッダー<br><code>12_全行同幅_単行見出し</code> | [PNG](../screenshots/C12.png) | [Markdown](C12/document.md) |
| C13 | 全行で同じ幅の横結合・2行ヘッダー<br><code>13_全行同幅_複行見出し</code> | [PNG](../screenshots/C13.png) | [Markdown](C13/document.md) |
| C14 | 明細の一部だけ横結合<br><code>14_明細だけ横結合</code> | [PNG](../screenshots/C14.png) | [Markdown](C14/document.md) |
| C15 | 明細の縦結合<br><code>15_明細だけ縦結合</code> | [PNG](../screenshots/C15.png) | [Markdown](C15/document.md) |
| C16 | 複数行ヘッダーで横結合位置がずれる<br><code>16_複行_ずれた横結合</code> | [PNG](../screenshots/C16.png) | [Markdown](C16/document.md) |
| C17 | 全行に直接塗りがある<br><code>17_全行色付き</code> | [PNG](../screenshots/C17.png) | [Markdown](C17/document.md) |
| C18 | 無色の先頭行・全行で同じ幅の横結合<br><code>18_無色_全行同幅横結合</code> | [PNG](../screenshots/C18.png) | [Markdown](C18/document.md) |
| C19 | 非表示の分割行を挟む同幅結合<br><code>19_隠れた分割行</code> | [PNG](../screenshots/C19.png) | [Markdown](C19/document.md) |
| C20 | 隣り合う2組の同幅結合<br><code>20_隣接する同幅結合</code> | [PNG](../screenshots/C20.png) | [Markdown](C20/document.md) |
| C21 | 左上だけ罫線のないマトリックス<br><code>21_左上無罫線のマトリックス</code> | [PNG](../screenshots/C21.png) | [Markdown](C21/document.md) |
| C22 | 通常表に続く、角だけ罫線のないマトリックス<br><code>22_通常表に続くマトリックス</code> | [PNG](../screenshots/C22.png) | [Markdown](C22/document.md) |
| C23 | 無罫線の注記と3列幅の結合見出し<br><code>23_注記と3列結合の見出し</code> | [PNG](../screenshots/C23.png) | [Markdown](C23/document.md) |
| C24 | 無罫線の注記と縦横結合を含む3段見出し<br><code>24_注記と縦横結合の3段見出し</code> | [PNG](../screenshots/C24.png) | [Markdown](C24/document.md) |

入力全体: [input.xlsx](../input.xlsx) · [document.md](../output/document.md) · [report.json](../output/report.json) · [実行記録](../run.json)
