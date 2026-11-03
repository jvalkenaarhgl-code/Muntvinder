import sys
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from facebook_marketplace import normalize_result, amount, fetch, QUERIES
import scanner


def indexed_result():
    return {
        "title":"Opel Corsa 2010 190.000 km €1.250 - Facebook Marketplace",
        "description":"Opel Corsa rijdt goed, kleine deuk in portier",
        "link":"https://www.facebook.com/marketplace/item/1234567890123456/"
    }


def test_only_real_indexed_marketplace_link_and_explicit_euro_price():
    d=normalize_result(indexed_result())
    assert d is not None
    assert d["id"]=="fb:1234567890123456"
    assert d["price"]==1250.0
    assert d["year"]==2010
    assert d["mileage"]==190000
    assert d["source"]=="Facebook Marketplace"
    assert d["price_unverified"] is True
    assert d["image_url"] is None
    assert d["facebook_discovery"]=="publieke_zoekindex"


def test_reject_missing_price_non_car_and_untrusted_url():
    d=indexed_result()
    d["title"]="Opel Corsa 2010 - Facebook Marketplace"
    assert normalize_result(d) is None
    d=indexed_result()
    d["link"]="https://other.example/marketplace/item/1234567890123456/"
    assert normalize_result(d) is None
    d=indexed_result()
    d["title"]="Tafel antiek 2010 €500"
    assert normalize_result(d) is None
    d=indexed_result()
    d["link"]="https://www.facebook.com/groups/12345678/posts/123456789"
    assert normalize_result(d) is None


def test_euro_price_parser_rejects_unqualified_numbers():
    assert amount("€ 1.500")==1500.0
    assert amount("EUR 950")==950.0
    assert amount("€ 9.000")==None
    assert amount("2010 220.000 km")==None


def test_free_search_uses_public_index_and_no_api_token():
    rss='''<?xml version="1.0"?><rss><channel><item>
        <title>Opel Corsa 2010 190.000 km €1.250 - Facebook Marketplace</title>
        <description>Goede auto, deuk in portier</description>
        <link>https://www.facebook.com/marketplace/item/1234567890123456/</link>
        </item></channel></rss>'''
    class Response:
        content=rss.encode("utf-8")
        def raise_for_status(self): pass
    class Session:
        def __init__(self):self.calls=0
        def get(self,url,**kwargs):
            assert url=="https://www.bing.com/search"
            assert kwargs["params"]["format"]=="rss"
            assert "Authorization" not in kwargs.get("headers",{})
            self.calls+=1
            return Response()
    session=Session()
    deals,status=fetch(session)
    assert session.calls==len(QUERIES)
    assert len(deals)==1
    assert status["status"]=="ok"
    assert "gratis" in status["provider"]
    assert "token" not in str(status).lower()


def test_empty_rss_does_not_invent_ads():
    class Response:
        content=b'<?xml version="1.0"?><rss><channel></channel></rss>'
        def raise_for_status(self):pass
    class Session:
        def get(self,*args,**kwargs):return Response()
    deals,status=fetch(Session())
    assert deals==[]
    assert status["status"]=="geen_passende_advertenties"


def test_indexed_snippet_price_not_alertworthy_even_with_good_comps():
    fb=normalize_result(indexed_result())
    mp=[scanner.parse_listing({
        "itemId":f"m12345678{i}",
        "title":"Opel Corsa 2010 180000 km",
        "vipUrl":f"/v/auto-s/m12345678{i}",
        "priceInfo":{"priceCents":p*100,"priceType":"FIXED"}
    }) for i,p in enumerate([1900,2100,2400,2600])]
    assert all(mp)
    item=scanner.value_deal(fb,mp,repair=100,other=100)
    ranked=scanner.sort_deals([item])
    assert ranked[0]["profit_low"] is not None
    assert ranked[0]["alert_candidate"] is False
    assert ranked[0]["price_unverified"] is True


def test_previous_free_results_reused_without_paid_service():
    found=normalize_result(indexed_result())
    prev={"facebook_status":{"status":"ok","count":1,
          "checked_at":"2099-01-01T00:00:00+00:00",
          "provider":"gratis openbare zoekmachine-index"},
          "deals":[found]}
    # Future timestamp is not a valid recent timestamp; simulate current time.
    from datetime import datetime,timezone
    prev["facebook_status"]["checked_at"]=datetime.now(timezone.utc).isoformat()
    with patch("facebook_marketplace.fetch",side_effect=AssertionError("No new query")):
        deals,status=scanner.facebook_entries(prev,None,1)
    assert len(deals)==1
    assert status["status"]=="bewaarde_resultaten"
