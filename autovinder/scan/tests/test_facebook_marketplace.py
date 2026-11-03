import sys
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))

from facebook_marketplace import normalize, fetch, input_payload
import scanner

def facebook_example(*, amount=950,currency="EUR",odometer=190000,unit="km"):
    return {
        "id":"1234567890123456",
        "url":"https://www.facebook.com/marketplace/item/1234567890123456/",
        "title":"Opel Corsa 2010 met deuk",
        "description":"Heeft deuk aan portier. Rijdt prima. Benzine",
        "price":{"amount":amount,"currency":currency,"formatted":"€950"},
        "location":{"city":"Almelo","state":"Overijssel"},
        "images":["https://scontent-ams2-1.xx.fbcdn.net/car/42.jpg"],
        "vehicle":{"make":"Opel","model":"Corsa","year":2010,
                   "odometer":{"value":odometer,"unit":unit},
                   "fuel":"benzine"},
        "is_live":True,"is_sold":False,"is_pending":False,
    }

def test_facebook_vehicle_price_mileage_and_photo():
    item=normalize(facebook_example())
    assert item["source"]=="Facebook Marketplace"
    assert item["price"]==950
    assert item["make"]=="Opel" and item["model"]=="Corsa"
    assert item["mileage"]==190000
    assert item["year"]==2010
    assert item["damage"] is True
    assert item["image_url"].startswith("https://scontent")
    assert item["id"]=="fb:1234567890123456"
    assert item["city"]=="Almelo"

def test_no_currency_confusion_or_sold_or_no_car():
    assert normalize(facebook_example(currency="USD")) is None
    item=facebook_example()
    item["is_sold"]=True
    assert normalize(item) is None
    item=facebook_example()
    item["url"]="https://untrusted.example/marketplace/item/1234567890123456/"
    assert normalize(item) is None
    item=facebook_example()
    item["title"]="Houten bureau 2010"
    item["vehicle"]={}
    assert normalize(item) is None

def test_american_miles_conversion():
    item=normalize(facebook_example(odometer=100000,unit="miles"))
    assert item["mileage"]==160934

def test_downpayment_is_not_treated_as_full_price():
    item=facebook_example()
    item["description"]="€950 aanbetaling op leaseovereenkomst"
    assert normalize(item) is None

def test_token_is_required_and_never_logged():
    class Session:
        def post(self,*args,**kwargs):
            raise AssertionError("Should not call API")
    data,status=fetch(Session(),token="")
    assert data==[]
    assert status["status"]=="niet_geconfigureerd"

def test_authenticated_api_integration_stub(monkeypatch):
    class Response:
        status_code=200
        def raise_for_status(self): pass
        def json(self):return [facebook_example(),facebook_example()]  # de-dup
    class Session:
        def post(self,url,**kwargs):
            assert url.startswith("https://api.apify.com/v2/actors/")
            assert kwargs["headers"]["Authorization"]=="Bearer sample-token"
            assert kwargs["params"]["maxTotalChargeUsd"]=="0.12"
            assert kwargs["json"]["vehicleSearches"]
            return Response()
    monkeypatch.setenv("AUTOVINDER_FB_COST_CAP_USD","0.12")
    data,status=fetch(Session(),token="sample-token")
    assert len(data)==1
    assert status["count"]==1
    assert "sample-token" not in repr(status)

def test_same_conservative_resale_model_for_facebook(monkeypatch,tmp_path):
    mp=[scanner.parse_listing({
        "itemId":f"m123456789{i}",
        "title":"Opel Corsa 2010 180000 km",
        "vipUrl":f"/v/auto-s/m123456789{i}",
        "priceInfo":{"priceCents":p*100,"priceType":"FIXED"}
        }) for i,p in enumerate([1800,2200,2400,2500])]
    facebook=normalize(facebook_example())
    assert all(mp)
    with patch.object(scanner,"get_market",return_value=(mp,[],2)),\
         patch.object(scanner,"facebook_entries",return_value=([facebook],{"status":"ok","count":1})),\
         patch.object(scanner,"OUTPUT",tmp_path/"feed.json"):
        out=scanner.run()
    d=next(x for x in out["deals"] if x["source"]=="Facebook Marketplace")
    assert d["comparables"]>=3
    assert d["resale_low"] is not None
    assert d["profit_low"] is not None
    assert out["source_counts"]["Facebook Marketplace"]==1
    assert out["facebook_status"]["status"]=="ok"

def test_apify_budget_and_locations():
    payload=input_payload()
    assert payload["maxPrice"]<=5000
    assert payload["fetchDetails"] is True
    assert payload["searchRadiusKm"]==65
    assert len(payload["vehicleSearches"])>=3
