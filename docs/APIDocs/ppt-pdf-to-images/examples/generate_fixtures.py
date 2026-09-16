#!/usr/bin/env python3
"""Self-authored Japanese fixtures. Requires python-pptx, Pillow and reportlab."""
from pathlib import Path
from io import BytesIO
from PIL import Image, ImageDraw
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.chart import XL_CHART_TYPE, XL_LEGEND_POSITION
from pptx.chart.data import CategoryChartData
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
from reportlab.lib.colors import HexColor, Color, white

ROOT = Path(__file__).resolve().parent
INPUT = ROOT / 'inputs'
INPUT.mkdir(exist_ok=True)
NAVY, BLUE, TEAL, INK, LIGHT = '143247', '2563EB', '089A8C', '263B4A', 'F1F6F9'


def illustration():
    image = Image.new('RGB', (900, 460), '#D7EDF2')
    draw = ImageDraw.Draw(image)
    draw.ellipse((540, -150, 1050, 380), fill='#B4DEE0')
    for i, (height, fill) in enumerate([(100, '#66AAA8'), (190, '#398B9D'), (260, '#216882'), (160, '#559CAF')]):
        x = 110 + i * 155
        draw.rounded_rectangle((x, 350 - height, x + 95, 350), radius=12, fill=fill)
    draw.line([(100, 300), (260, 220), (420, 230), (590, 105), (790, 60)], fill='#143247', width=10)
    for x, y in [(100, 300), (260, 220), (420, 230), (590, 105), (790, 60)]:
        draw.ellipse((x-10, y-10, x+10, y+10), fill='white', outline='#143247', width=4)
    data = BytesIO()
    image.save(data, 'PNG')
    return data.getvalue()


def ppt_text(parent, text, x, y, w, h, size=18, color=INK, bold=False, font='Meiryo'):
    box = parent.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = Inches(.02)
    for n, line in enumerate(text.split('\n')):
        p = tf.paragraphs[0] if n == 0 else tf.add_paragraph()
        p.text = line
        p.font.name = font
        p.font.size = Pt(size)
        p.font.bold = bold
        p.font.color.rgb = RGBColor.from_string(color)
        p.space_after = Pt(8)
    return box


def ppt_rect(parent, x, y, w, h, fill=LIGHT, kind=MSO_SHAPE.ROUNDED_RECTANGLE):
    s = parent.shapes.add_shape(kind, Inches(x), Inches(y), Inches(w), Inches(h))
    s.fill.solid(); s.fill.fore_color.rgb = RGBColor.from_string(fill)
    s.line.fill.background()
    return s


def ppt_base(prs, number, title, sub):
    s = prs.slides.add_slide(prs.slide_layouts[6])
    s.background.fill.solid(); s.background.fill.fore_color.rgb = RGBColor(255,255,255)
    ppt_rect(s, 0, 0, 13.333, .13, TEAL, MSO_SHAPE.RECTANGLE)
    ppt_text(s, 'CONVERTX2X / 自作の動作確認資料', .55, .35, 11, .35, 11, TEAL, True)
    ppt_text(s, title, .55, .88, 12, .7, 30, NAVY, True)
    ppt_text(s, sub, .55, 1.65, 12, .55, 14)
    ppt_text(s, f'サンプルの数値は架空です    •    2026.09    /    {number:02d}', .55, 7.03, 12, .3, 10)
    return s


def make_pptx():
    prs = Presentation(); prs.slide_width = 12192000; prs.slide_height = Inches(7.5)
    s = ppt_base(prs, 1, '事業レポート｜基本レイアウト', '日本語・英数字・太字・明朝体・埋め込み画像を含む、一般的なスライドです。')
    ppt_rect(s, .55, 2.4, 5.3, 4.1)
    ppt_text(s, '変換で確認するポイント', .85, 2.7, 4.75, .5, 22, NAVY, True)
    ppt_text(s, '01  日本語の見出しと本文\n02  Sales 128.6 / +18.4%\n03  図形と画像の重なり\n04  元のスライド比率 16:9', .85, 3.4, 4.75, 2.8, 20)
    s.shapes.add_picture(BytesIO(illustration()), Inches(6.3), Inches(2.45), Inches(6.3), Inches(3.22))
    ppt_text(s, '成長を支える、毎日の改善。', 6.45, 5.9, 6, .5, 22, NAVY, False, 'Yu Mincho')

    s = ppt_base(prs, 2, '複雑な例｜四半期ダッシュボード', 'ネイティブ表・グラフ・グループ図形・回転・複数の文字スタイルを同じページに配置。')
    for x, label, value, fill in [(0.55,'売上高','128.6 百万円',NAVY),(4.85,'計画達成率','108.2%',TEAL),(9.15,'継続率','96.4%',BLUE)]:
        ppt_rect(s,x,2.35,3.65,1.1,fill)
        ppt_text(s,label,x+.2,2.48,3.25,.3,12,'FFFFFF')
        ppt_text(s,value,x+.2,2.84,3.25,.5,23,'FFFFFF',True)
    table=s.shapes.add_table(6,4, Inches(.55), Inches(3.8), Inches(6.25), Inches(2.5)).table
    rows=[['エリア','売上（百万円）','前期比','判定'],['東京','51.4','+14.2%','順調'],['大阪','32.8','+21.7%','順調'],['福岡','24.6','+18.5%','確認中'],['札幌','19.8','+20.1%','順調'],['合計','128.6','+18.4%','計画超過']]
    for r,row in enumerate(rows):
        for c,text in enumerate(row):
            cell=table.cell(r,c); cell.text=text
            cell.fill.solid();cell.fill.fore_color.rgb=RGBColor.from_string(NAVY if r==0 else ('E8F5F2' if r==5 else ('F1F6F9' if r%2 else 'FFFFFF')))
            for p in cell.text_frame.paragraphs:
                p.font.name='Meiryo';p.font.size=Pt(12);p.font.bold=r in (0,5);p.font.color.rgb=RGBColor.from_string('FFFFFF' if r==0 else INK)
    data=CategoryChartData();data.categories=['Q1','Q2','Q3','Q4'];data.add_series('2025',[65,78,91,108]);data.add_series('2026',[75,94,117,129])
    chart=s.shapes.add_chart(XL_CHART_TYPE.COLUMN_CLUSTERED, Inches(7.2), Inches(3.8), Inches(3.55), Inches(2.55), data).chart
    chart.has_legend=True;chart.legend.position=XL_LEGEND_POSITION.BOTTOM
    chart.chart_style=10
    ppt_text(s,'PowerPointのネイティブグラフ',7.18,6.4,4.2,.3,10)
    group=s.shapes.add_group_shape()
    box=group.shapes.add_shape(MSO_SHAPE.HEXAGON, Inches(11), Inches(4.15), Inches(1.5), Inches(1.15))
    box.fill.solid();box.fill.fore_color.rgb=RGBColor.from_string('FBBF24');box.line.fill.background()
    ppt_text(group,'重点施策',11.06,4.48,1.38,.35,13,NAVY,True)
    group.rotation=12
    note=ppt_text(s,'回転 12°',11.0,5.7,1.8,.4,12,TEAL,True);note.rotation=12

    s=ppt_base(prs,3,'複雑な例｜業務フローと注記','図形の形・回転・位置関係と、小さな注記の可読性を確認します。')
    stages=[(.75,'依頼を受信',BLUE),(4.8,'内容を検証',TEAL),(8.85,'結果を保存',NAVY)]
    for x,label,color in stages:
        ppt_rect(s,x,2.75,3.55,1.5,color)
        ppt_text(s,label,x+.25,3.22,3.05,.55,23,'FFFFFF',True)
        if x<8:
            ppt_rect(s,x+3.65,3.25,.32,.45,'8DA6B5',MSO_SHAPE.CHEVRON)
    ppt_rect(s,.75,4.8,11.65,1.65)
    ppt_text(s,'確認メモ',1.05,5.03,2,.4,17,NAVY,True)
    ppt_text(s,'・2枚目の棒グラフは画像ではなく、PowerPointのグラフオブジェクトです。\n・日本語は同梱フォントへ代替されるため、字幅や折り返しは元のOfficeと異なる場合があります。',1.05,5.55,10.9,.8,13)
    prs.save(INPUT/'quarterly-review.pptx')


def make_pdf():
    pdfmetrics.registerFont(UnicodeCIDFont('HeiseiKakuGo-W5'))
    pdfmetrics.registerFont(UnicodeCIDFont('HeiseiMin-W3'))
    c=canvas.Canvas(str(INPUT/'operations-report.pdf'), pagesize=(595.28,841.89), invariant=1)
    c.setTitle('業務レポート / convertX2X 自作サンプル'); c.setAuthor('convertX2X')
    def text(x,y,t,size=12,font='HeiseiKakuGo-W5',color=INK):
        c.setFillColor(HexColor('#'+color));c.setFont(font,size);c.drawString(x,y,t)
    def rect(x,y,w,h,color):
        c.setFillColor(HexColor('#'+color));c.rect(x,y,w,h,fill=1,stroke=0)
    def base(n,title,landscape=False):
        width,height=(841.89,595.28) if landscape else (595.28,841.89)
        rect(0,height-10,width,10,TEAL)
        text(38,height-44,'CONVERTX2X / 自作の動作確認資料',10,color=TEAL)
        text(38,height-90,title,25,color=NAVY)
        text(38,23,f'架空データ  /  2026.09  /  {n:02d}',9)
        return width,height
    base(1,'業務レポート｜日本語の基本例')
    text(38,716,'PDFの本文・図形・画像・リンクを1枚のPNGへ。',13)
    rect(38,583,519,95,LIGHT)
    text(57,646,'月次サマリー',18,color=NAVY)
    text(57,617,'処理件数 1,248 件  /  完了率 99.2%  /  平均 2.4 秒',14)
    from reportlab.lib.utils import ImageReader
    c.drawImage(ImageReader(BytesIO(illustration())),38,277,width=519,height=265)
    text(38,232,'持続的な改善と、分かりやすい報告。',20,'HeiseiMin-W3',NAVY)
    text(38,188,'日本語ゴシック・明朝、数値 128.6、記号 ± × → を確認。',12)
    text(38,154,'https://example.com/report（サンプルリンク）',11,color=BLUE)
    c.linkURL('https://example.com/report',(38,150,380,166),relative=0)
    c.showPage();base(2,'複雑な例｜明細・グラフ・透過')
    text(38,716,'細い罫線、色付き行、桁区切り、日本語注記、ベクター図形。',12)
    cols=[38,230,320,408,557];top=684;rowh=29
    rows=[['項目','数量','単価','金額'],['クラウド利用料','120','1,200','144,000'],['データ変換処理','2,400','18','43,200'],['バックアップ','31','800','24,800'],['サポート対応','8','5,000','40,000'],['ストレージ','340','24','8,160'],['監査ログ保管','12','640','7,680'],['小計','','','267,840'],['消費税 10%','','','26,784'],['合計','','','294,624']]
    for i,row in enumerate(rows):
        y=top-(i+1)*rowh
        rect(38,y,519,rowh,NAVY if i==0 else ('DFF2ED' if i==9 else (LIGHT if i%2 else 'FFFFFF')))
        for j,v in enumerate(row):text(cols[j]+10,y+10,v,11,color='FFFFFF' if i==0 else INK)
    c.setLineWidth(.4);c.setStrokeColor(HexColor('#B8C8D2'))
    for x in cols:c.line(x,top,x,top-10*rowh)
    for i in range(11):c.line(38,top-i*rowh,557,top-i*rowh)
    text(38,359,'処理件数の推移（ベクター描画）',15,color=NAVY)
    for i,v in enumerate([80,130,110,180,220,260]):
        rect(57+i*80,161,42,v*.55,TEAL if i%2 else BLUE);text(62+i*80,141,f'{i+4}月',10)
    c.saveState();c.translate(320,480);c.rotate(22);c.setFillColor(HexColor('#'+TEAL));c.setFont('HeiseiKakuGo-W5',43);c.setFillAlpha(.18);c.drawString(-145,0,'確認用 / SAMPLE');c.restoreState()
    text(38,96,'注記：このPDFの日本語CIDフォントは埋め込んでいません。',11)
    text(38,74,'表示時は利用可能な代替フォントが選択されます。',11)
    c.showPage();c.setPageSize((841.89,595.28));base(3,'複雑な例｜横長ページ・回転・クリッピング',True)
    text(38,464,'同じPDFに縦長A4と横長A4を混在。ページごとの縦横比を維持します。',13)
    for i,(label,color) in enumerate([('入力',BLUE),('検証',TEAL),('保存',NAVY)]):
        x=48+i*270;rect(x,279,215,118,color);text(x+80,329,label,26,color='FFFFFF')
        if i<2:
            c.setStrokeColor(HexColor('#8DA6B5'));c.setLineWidth(3);c.line(x+227,338,x+254,338)
            c.line(x+245,346,x+254,338);c.line(x+245,330,x+254,338)
    c.saveState();p=c.beginPath();p.roundRect(48,101,285,135,15);c.clipPath(p,stroke=0,fill=0)
    c.drawImage(ImageReader(BytesIO(illustration())),28,90,width=345,height=176);c.restoreState()
    text(366,216,'切り抜き画像と回転文字',17,color=NAVY)
    text(366,180,'細線 0.4pt / 日本語・英数字 / 透過色',12)
    c.saveState();c.translate(630,114);c.rotate(-12);text(-120,0,'回転 -12° の文字',20,color=TEAL);c.restoreState()
    c.save()


if __name__ == '__main__':
    make_pptx();make_pdf()
    print('Created quarterly-review.pptx (3 slides), operations-report.pdf (3 pages)')
