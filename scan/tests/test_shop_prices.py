import sys
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))

from shop_prices import exact_year, parse_catalog, match_shops

BILDTMUNT = """
<div class="product">
  <a href="/products/proofset-2018"><h3>Proofset Nederland 2018</h3></a>
  <span class="price">€ 69,95</span><span>In winkelwagen</span>
</div>
<div class="product">
  <a href="/products/2018-zonder-omhoes">Proofset Nederland 2018 zonder omhoes</a>
  <span class="price">€ 9,95</span><span>In winkelwagen</span>
</div>
<div class="product">
  <a href="/products/2019">Proofset Nederland 2019</a>
  <span>Uitverkocht</span><span class="price">€ 20,00</span>
</div>
<div class="product">
  <a href="/products/2004-2006">Complete Proofsets Nederland 2004-2006</a>
  <span class="price">€ 80,00</span>
</div>
"""

HANSMUNT = """
<ul class="products">
<li class="product">
  <a class="woocommerce-loop-product__link" href="https://hansmunt.nl/2020-proof-set">
    <h2>Euromunten / Nederland / 2020 / Proof Set</h2>
  </a>
  <span class="price"><span class="amount"><bdi>€54,95</bdi></span></span>
  <a class="add_to_cart_button">In winkelwagen</a>
</li>
<li class="product outofstock">
  <a href="https://hansmunt.nl/2021-proof-set"><h2>Euromunten / Nederland / 2021 / Proof Set</h2></a>
  <span class="price">€14,95</span><div>Melding bij beschikbaarheid</div>
</li>
</ul>
"""

def test_exact_edition_excludes_similar_but_nonidentical_sets():
    assert exact_year("Proofset Nederland 2018")==2018
    assert exact_year("Nederland proofjaarset 2022")==2022
    assert exact_year("Euromunten / Nederland / 2020 / Proof Set")==2020
    assert exact_year("Proofset Nederland 2018 zonder omhoes") is None
    assert exact_year("Nederland 2016 / Proof / Alle 8 Munten In Capsule") is None
    assert exact_year("Prooflike set Nederland 1999") is None
    assert exact_year("Complete Proofsets Nederland 2004-2006") is None
    assert exact_year("Nederland UNC set 2018") is None


def test_extracts_prices_and_ignores_soldout_or_incomplete():
    offers=parse_catalog(BILDTMUNT,'Bildtmunt','https://www.bildtmunt.com/nederlandse-munten-knm/proof-sets-nederland')
    assert len(offers)==1
    assert offers[0]['year']==2018
    assert offers[0]['price']==69.95
    assert offers[0]['url']=='https://www.bildtmunt.com/products/proofset-2018'
    assert offers[0]['availability']=='beschikbaar'


def test_woocommerce_price_and_stock_handling():
    offers=parse_catalog(HANSMUNT,'Hansmunt','https://hansmunt.nl/product-categorie/euromunten/euromunten-nederland/nederland-proof-sets/')
    assert len(offers)==1
    assert offers[0]['year']==2020
    assert offers[0]['price']==54.95
    assert offers[0]['availability']=='beschikbaar'


def test_shop_comparison_does_not_call_shop_price_a_sales_value():
    deals=[{'id':'a','years':[2020],'bundle':False,'set_type':'euro','purchase_total':40.00},
           {'id':'b','years':[2019],'bundle':False,'set_type':'euro','purchase_total':20},
           {'id':'c','years':[2020,2021],'bundle':True,'set_type':'euro','purchase_total':40}]
    shop={'checked_at':'2026-11-03T12:00:00+00:00','offers':[
        {'year':2020,'price':54.95,'shop':'Hansmunt','url':'https://hansmunt.nl/2020','availability':'beschikbaar'},
        {'year':2020,'price':45.00,'shop':'Winkel Z','url':'https://example.com/2020','availability':'onbevestigd'}
    ]}
    match_shops(deals,shop)
    assert deals[0]['shop_price']==45.00
    assert deals[0]['shop_difference']==5.00
    assert deals[0]['shop_matches']==2
    assert deals[1]['shop_price'] is None
    assert deals[2]['shop_price'] is None


def test_herdenkingsmunt_2_euro_200_jaar_not_read_as_200_euro_price():
    html = """
    <ul class="products">
      <li class="product outofstock">
        <a href="https://hansmunt.nl/proof-2013">
          <h2>Euromunten / Nederland / 2013 / Proof Set / Incl 2€ 200 Jaar Nederland</h2>
        </a>
        <span>Melding bij beschikbaarheid</span>
      </li>
      <li class="product outofstock">
        <a href="https://hansmunt.nl/proof-2012">
          <h2>Euromunten / Nederland / 2012 / Proof Set / Incl 2€ 10 Jaar Euro</h2>
        </a>
        <span>Melding bij beschikbaarheid</span>
      </li>
      <li class="product">
        <a href="https://hansmunt.nl/proof-2011">
          <h2>Euromunten / Nederland / 2011 / Proof Set / Incl 2€ Erasmus</h2>
        </a>
        <span class="price">€69,95</span>
        <span>In winkelwagen</span>
      </li>
    </ul>
    """
    offers = parse_catalog(html, "Hansmunt", "https://hansmunt.nl/nl/proof/")
    assert [o["year"] for o in offers] == [2011]
    assert offers[0]["price"] == 69.95


def test_lightspeed_gallery_cards_and_sold_out():
    html = """
    <div class="home_page_listing">
      <section class="products_gallery_row">
        <div class="products_gallery_header">
          <div class="products_gallery_row_name product_name_default">
            <a class="products_name_link" href="/proofset-2018">Proofset Nederland 2018</a>
          </div>
        </div>
        <div class="products_gallery_row_price">Prijs: 124,00 <span>€124,00</span></div>
        <span>In winkelmand</span>
      </section>
      <section class="products_gallery_row">
        <div class="products_gallery_row_name">
          <a href="/proofset-2019">Proofset Nederland 2019</a>
        </div>
        <div class="products_gallery_row_price">Prijs: 20,00 <span>€20,00</span></div>
        <span>Uitverkocht</span>
      </section>
    </div>
    """
    rows = parse_catalog(html, "De Amsterdamse Munt", "https://www.deamsterdamsemunt.nl/shop")
    assert len(rows) == 1
    assert rows[0]["year"] == 2018
    assert rows[0]["price"] == 124.00
    assert rows[0]["availability"] == "beschikbaar"
