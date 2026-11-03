"""Optional Facebook Marketplace provider for AutoVinder.

Uses the third-party Apify Actor parsebird/facebook-marketplace-scraper.
The user's own APIFY_TOKEN must be installed as a GitHub Actions secret.
No passwords, session cookies, login-wall bypass, or Facebook account access.

Provider terms and availability may change; ads are only included on real responses.
"""
from __future__ import annotations

import os
import re
from datetime import datetime, timezone
from urllib.parse import urlparse
import requests

ACTOR="parsebird~facebook-marketplace-scraper"
API_URL=f"https://api.apify.com/v2/actors/{ACTOR}/run-sync-get-dataset-items"
LOCATIONS=(
    "Rijssen, Overijssel, Netherlands",
    "Zwolle, Overijssel, Netherlands",
    "Utrecht, Netherlands",
    "Eindhoven, Netherlands",
    "Rotterdam, Netherlands",
)

def input_payload():
    max_each=max(2,min(30,int(os.getenv("AUTOVINDER_FB_LISTINGS_PER_CITY","10"))))
    return {
        "vehicleSearches":[{"make":"All","location":place} for place in LOCATIONS],
        "maxPrice":min(5000,max(100,int(os.getenv("AUTOVINDER_FB_MAX_PRICE","5000")))),
        "maxListings":max_each,
        "fetchDetails":True,
        "searchRadiusKm":65,
        "deduplicateListings":True,
        "dateListed":"last_7_days",
        "sortBy":"creation_time_descend",
        "availability":"available",
        "monitoringMode":False
    }

def to_number(v):
    if isinstance(v,(float,int)) and not isinstance(v,bool):return float(v)
    if isinstance(v,str):
        m=re.search(r"(\d[\d\s.,]*)",v)
        if m:
            raw=m.group(1).strip().replace(" ","")
            if "," in raw and "." in raw: raw=raw.replace(".","").replace(",",".")
            elif "," in raw:
                lhs,rhs=raw.rsplit(",",1)
                raw=raw.replace(",","") if len(rhs)==3 else raw.replace(",",".")
            elif "." in raw and len(raw.rsplit(".",1)[1])==3:raw=raw.replace(".","")
            try:return float(raw)
            except ValueError:pass
    return None

def normalize(record):
    """Convert the Actor's documented result fields to AutoVinder's car schema."""
    if not isinstance(record,dict):return None
    if record.get("is_sold") or record.get("is_pending") or record.get("is_live") is False:
        return None
    title=str(record.get("title") or "").strip()
    link=str(record.get("url") or "").strip()
    try: host=(urlparse(link).hostname or "").lower()
    except ValueError:return None
    if urlparse(link).scheme!="https" or not (host=="facebook.com" or host.endswith(".facebook.com")):
        return None
    match=re.search(r"/marketplace/item/(\d+)",link)
    listing_id=str(record.get("id") or (match.group(1) if match else "")).strip()
    if not listing_id or not title:return None

    pr=record.get("price")
    currency="EUR"
    if isinstance(pr,dict):
        currency=str(pr.get("currency") or "EUR").upper()
        price=to_number(pr.get("amount"))
    else: price=to_number(pr)
    if currency not in ("EUR","€") or price is None or price<100 or price>5000:
        return None

    # Reuse the exact same car identifying, damage, and valuation rules as Marktplaats.
    # Imports are inside normalize to avoid a scanner/provider circular import.
    from scanner import (canonical_make_model, YEAR, mileage, DAMAGE, HIGH_RISK,
                         BLOCK, safe_image)
    vehicle=record.get("vehicle") if isinstance(record.get("vehicle"),dict) else {}
    description=str(record.get("description") or "")[:400]
    risktext=title+" "+description
    if BLOCK.search(risktext):return None
    make=str(vehicle.get("make") or "").strip()
    model=str(vehicle.get("model") or "").strip()
    if make and model:
        make,model=canonical_make_model(f"{make} {model}",{"make":make,"model":model})
    else:
        make,model=canonical_make_model(title)
    if not make or not model:return None

    year=vehicle.get("year") or vehicle.get("model_year")
    if year is not None:
        try: year=int(year)
        except (TypeError,ValueError):year=None
    if not year:
        matchyear=YEAR.search(title+" "+description)
        year=int(matchyear.group(0)) if matchyear else None
    if year is not None and not (1980<=year<=2027):year=None

    odometer=vehicle.get("odometer")
    mileage_km=None
    if isinstance(odometer,dict):
        val=to_number(odometer.get("value"))
        unit=str(odometer.get("unit") or "").lower()
        if val is not None:
            mileage_km=round(val*(1.609344 if unit in ("mi","mile","miles") else 1))
    elif odometer is not None:
        mileage_km=round(to_number(odometer) or 0) or None
    if mileage_km is None:
        mileage_km=mileage(str(vehicle.get("mileage") or "")) or mileage(title+" "+description)
    if mileage_km is not None and not (0<mileage_km<=999999):mileage_km=None

    location=record.get("location") if isinstance(record.get("location"),dict) else {}
    image_urls=[]
    for img in (record.get("images") or [])[:5]:
        if isinstance(img,dict):img=img.get("url") or img.get("src")
        if not isinstance(img,str):continue
        u=urlparse(img)
        hostname=(u.hostname or "").lower()
        if u.scheme=="https" and (hostname=="fbcdn.net" or hostname.endswith(".fbcdn.net")
                or hostname=="facebook.com" or hostname.endswith(".facebook.com")):
            if img not in image_urls:image_urls.append(img[:1500])
    damage=bool(DAMAGE.search(risktext))
    highrisk=bool(HIGH_RISK.search(risktext))
    price_unverified=bool(price<300 or re.search(
        r"aanbetaling|vanafprijs|down\s*payment|per\s*maand|deposit",risktext,re.I))
    return {
        "id":"fb:"+listing_id,"source":"Facebook Marketplace",
        "title":title[:180],"description":description,"make":make.title(),
        "model":model.upper() if model.isdigit() else model.capitalize(),
        "year":year,"mileage":mileage_km,"price":round(price,2),
        "price_kind":"FIXED","fuel":str(vehicle.get("fuel") or ""),
        "transmission":str(vehicle.get("transmission") or ""),
        "city":str(location.get("city") or ""),"postcode":str(location.get("postal_code") or ""),
        "url":link,"image_url":image_urls[0] if image_urls else None,
        "image_urls":image_urls,"damage":damage,"high_risk":highrisk,
        "damage_note":("Ernstig technisch defect; monteurcontrole nodig" if highrisk else
                       "Schade/gebrek genoemd; reparatiekosten eerst bepalen" if damage else ""),
        "seller_name":"","price_unverified":price_unverified,
        "price_warning":"Vraagprijs controleren" if price_unverified else "",
        "is_bid":False,
        "facebook_checked_at":datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }

def fetch(session=None, token=None):
    """Return normalized listings and source status. Never put the API token in data or logs."""
    token=(token if token is not None else os.getenv("APIFY_TOKEN","")).strip()
    if not token:return [],{"status":"niet_geconfigureerd","count":0}
    session=session or requests.Session()
    budget=max(.01,min(.50,float(os.getenv("AUTOVINDER_FB_COST_CAP_USD","0.12"))))
    payload=input_payload()
    query={"maxTotalChargeUsd":f"{budget:.2f}","maxItems":min(100,len(LOCATIONS)*payload["maxListings"])}
    try:
        response=session.post(API_URL,json=payload,params=query,timeout=(20,235),
                              headers={"Authorization":f"Bearer {token}",
                                       "Accept":"application/json",
                                       "Content-Type":"application/json"})
        if response.status_code in (401,403):
            return [],{"status":"toegang_geweigerd","count":0,"error":"Apify-toegang geweigerd; controleer API-token en account"}
        response.raise_for_status()
        raw=response.json()
        if not isinstance(raw,list):
            raise ValueError("Provider retourneerde geen advertentielijst")
        result={}
        for listing in raw:
            row=normalize(listing)
            if row:result[row["id"]]=row
        return list(result.values()),{
            "status":"ok" if result else "geen_passende_advertenties",
            "count":len(result),
            "checked_at":datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "provider":"Apify: "+ACTOR,
            "price_limit":payload["maxPrice"],"locations":list(LOCATIONS),
            "cost_cap_usd":budget
        }
    except (requests.RequestException,ValueError,TypeError) as e:
        return [],{"status":"fout","count":0,
                   "error":"Facebook-provider tijdelijk niet beschikbaar: "+type(e).__name__}
