import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from scanner import (canonical_make_model, mileage, parse_listing, comparables_for, value_deal,
                     sort_deals, flatten_attributes, extract_images, run)

def listing(id,make="Peugeot",model="107",year=2010,km=175000,price=1100,damaged=False,kind="FIXED"):
    obj={
        "itemId":str(id),
        "title":f"{make} {model} {year} {km} km"+(" schade" if damaged else ""),
        "description":"Auto rijdt goed" if not damaged else "Auto met deuk schade rechterdeur",
        "vipUrl":f"/v/autos/{id}-test",
        "priceInfo":{"priceCents":int(price*100),"priceType":kind},
        "attributes":[{"key":"bouwjaar","value":str(year)},
                      {"key":"kilometerstand","value":f"{km:,} km"}],
        "pictures":[{"largeUrl":"https://images.marktplaats.com/1.jpg"}]
    }
    return parse_listing(obj)

def test_basic_used_car_parsing_and_price():
    row=listing("m2451251234")
    assert row["year"]==2010
    assert row["mileage"]==175000
    assert row["price"]==1100
    assert row["make"]=="Peugeot"
    assert row["model"]=="107"
    assert row["image_url"].startswith("https:")
    assert row["damage"] is False

def test_damage_accepted_and_highrisk_flag():
    damaged=listing("m2451251235",damaged=True)
    assert damaged is not None and damaged["damage"]
    risk=parse_listing({"itemId":"m2451251236",
                        "title":"Opel Corsa 2008 motor kapot start niet",
                        "description":"Motor loopt niet",
                        "vipUrl":"/v/autos/m2451251236-test",
                        "priceInfo":{"priceCents":40000,"priceType":"FIXED"}})
    assert risk["high_risk"] is True

def test_no_lease_or_bait_listing():
    obj={"itemId":"m2451251299","title":"Peugeot 107 auto private lease",
        "vipUrl":"/v/autos/m2451251299-test",
        "priceInfo":{"priceCents":45000,"priceType":"FIXED"}}
    assert parse_listing(obj) is None

def test_comparables_match_model_year_and_mileage_not_damage():
    target=listing("m1",km=190000,price=800)
    comps=[listing(f"m{i+10}",year=2010,km=185000,price=p)
           for i,p in enumerate([1600,1900,2300,2600])]
    comps.append(listing("m80",year=2021,km=180000,price=3000))
    comps.append(listing("m81",year=2010,km=390000,price=900))
    comps.append(listing("m82",year=2010,km=188000,price=600,damaged=True))
    result=value_deal(target,[target]+comps,repair=300,other=175)
    assert result["comparables"]==4
    # median 2100; low 1575, high 1848
    assert result["resale_low"]==1575
    assert result["resale_high"]==1848
    assert result["total_investment"]==1275
    assert result["profit_low"]==300
    assert result["profit_high"]==573

def test_no_pretend_profit_without_relevant_sold_data():
    a=listing("m1",price=1000)
    r=value_deal(a,[a],repair=0,other=0)
    assert r["resale_low"] is None
    assert r["profit_low"] is None

def test_very_good_deal_top_and_alert_only_fixed():
    target=listing("m1",price=800)
    comps=[listing(f"m{i+10}",price=2000+i*50) for i in range(4)]
    items=[value_deal(x,[target]+comps,repair=100,other=100) for x in [target]+comps]
    ranked=sort_deals(items)
    assert ranked[0]["id"]=="mp:m1"
    assert ranked[0]["alert_candidate"] is True
    assert ranked[0]["profit_low"]>0
    assert ranked[0]["rank"]==1

def test_brand_parser():
    assert canonical_make_model("VW Golf 1.4 uit 2010")==("volkswagen","golf")
    assert canonical_make_model("Opel Corsa 2010 benzine")==("opel","corsa")
    assert mileage("220.000 km") == 220000
    assert mileage("105000 kilometer") == 105000
