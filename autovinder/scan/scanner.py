"""AutoVinder: feitelijke Marktplaats-autoselectie met voorzichtig taxatiemodel.
Geen Facebook scraping; Facebook wordt handmatig als link in de Android-app aangeboden.
De JSON-feed bevat alleen geobserveerde autoadvertenties, geen verzonnen verkoopprijzen.
"""
from __future__ import annotations
import json, math, os, re, statistics, time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlparse, quote
import requests

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/"docs"/"feed.json"
ENDPOINT="https://www.marktplaats.nl/lrp/api/search"
HEADERS={"User-Agent":"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36",
         "Accept":"application/json, text/plain, */*", "Accept-Language":"nl-NL,nl;q=0.9",
         "Referer":"https://www.marktplaats.nl/"}
MAKES=["volkswagen","opel","ford","peugeot","renault","citroen","citroën","toyota",
       "fiat","hyundai","kia","seat","skoda","škoda","nissan","suzuki","mazda",
       "honda","bmw","mercedes","audi","volvo","mitsubishi","dacia","daewoo",
       "chevrolet","smart","mini","saab","subaru","seat","chrysler"]
ALIASES={"vw":"volkswagen","mercedes-benz":"mercedes","citroën":"citroen","škoda":"skoda"}
STOP_WORDS={"1.0","1.1","1.2","1.3","1.4","1.5","1.6","1.8","1.9","2.0","2.2",
            "benzine","diesel","automaat","handgeschakeld","schade","apk","auto","occasion"}
YEAR=re.compile(r"(?<!\d)(?:19[89]\d|20[0-2]\d)(?!\d)")
KM=re.compile(r"(?<!\d)(\d{1,3}(?:[., ]\d{3})+|\d{4,6})\s*(?:km\b|kilometer)",re.I)
DAMAGE=re.compile(r"\b(schade|deuk|blikschade|motorschade|storing|kapot|defect|"
                  r"motorlamp|start\s+niet|loopt\s+niet|afgekeurd|geen\s+apk|"
                  r"zonder\s+(?:geldige\s+)?apk|motor\s+niet\s+goed|"
                  r"roest|aanrijding|versnellingsbak|koppeling\s+slipt)\b",re.I)
HIGH_RISK=re.compile(r"motorschade|motor\s+(?:kapot|niet\s+goed)|distributie|"
                     r"versnellingsbak\s+kapot|start\s+niet|loopt\s+niet|"
                     r"zonder\s+(?:geldige\s+)?apk|total\s+loss|afgekeurd|sloop",re.I)
BLOCK=re.compile(r"\b(gezocht|zoektocht|onderdelen|losse\s+velgen|motorblok\s+los|"
                 r"aanbetaling|per\s+maand|maandbedrag|private\s+lease|"
                 r"financial\s+lease|gezocht\s+auto)\b",re.I)
SAMPLE_QUERIES=["peugeot 107","peugeot 206","opel corsa","ford fiesta","volkswagen polo",
                "volkswagen golf","renault clio","citroen c1","fiat panda","toyota aygo",
                "hyundai getz","kia picanto"]
MAX_COLLECT_PRICE=int(os.getenv("AUTOVINDER_MAX_SCAN_PRICE","5000"))
COMP_MAX_PRICE=int(os.getenv("AUTOVINDER_MAX_COMPARABLE_PRICE","12500"))
LIMIT=max(20,min(100,int(os.getenv("AUTOVINDER_PAGE_SIZE","100"))))
DEFAULT_FILTERS={"max_price":1500,"max_km":250000,"repair":250,"other_costs":175,
                 "min_year":1995,"max_distance_km":0,"postcode":"",
                 "include_damage":True,"allow_unknown_km":False,"keywords":""}

def euro(value):
    try:
        return round(float(value),2)
    except (TypeError,ValueError): return None

def extract_price(obj):
    info=obj.get("priceInfo") or {}
    kind=str(info.get("priceType") or obj.get("price_type") or "").upper()
    cents=info.get("priceCents",obj.get("price_cents"))
    try:
        price=int(cents)/100 if cents is not None else float(obj.get("price",0))
        return (round(price,2),kind) if price>0 else (None,kind)
    except (TypeError,ValueError):return None,kind

def canonical_make_model(title, attrs=None):
    text=re.sub(r"[^\w .-]"," ",str(title).lower()).replace("mercedes benz","mercedes")
    words=text.split()
    if attrs:
        mark=str(attrs.get("merk") or attrs.get("make") or "").strip().lower()
        model=str(attrs.get("model") or "").strip().lower()
        if mark and model:
            mark=ALIASES.get(mark,mark)
            return mark,model.split()[0]
    for i,w in enumerate(words):
        mark=ALIASES.get(w,w)
        if mark not in MAKES:continue
        for token in words[i+1:i+6]:
            if token in ("de","een","auto","met","of","voor","uit","en") or token in STOP_WORDS:
                continue
            if token.isdigit() and len(token)==4 and int(token)>1980:continue
            if re.fullmatch(r"[a-z][a-z0-9-]*|\d{2,3}",token):
                return mark,token
    return None,None

def flatten_attributes(obj):
    result={}
    # Marktplaats wisselt tussen 'attributes', objectAttributes en gegroepeerde velden.
    queue=[obj.get("attributes"),obj.get("attributeGroups"),obj.get("objectAttributes"),
           obj.get("extendedAttributes")]
    while queue:
        a=queue.pop(0)
        if isinstance(a,list): queue.extend(a);continue
        if not isinstance(a,dict):continue
        key=a.get("key") or a.get("name") or a.get("label")
        value=a.get("value") or a.get("values") or a.get("labelValue")
        if key is not None and value is not None:
            if isinstance(value,list):
                value=", ".join(str(t.get("label") or t.get("value") or t)
                                if isinstance(t,dict) else str(t) for t in value)
            if isinstance(value,dict): value=value.get("label") or value.get("value") or str(value)
            result[str(key).strip().lower()]=str(value)
        for k,v in a.items():
            if isinstance(v,(list,dict)):queue.append(v)
            elif k.lower() in ("merk","model","bouwjaar","kilometerstand","mileage","fuel","brandstof"):
                result[k.lower()]=str(v)
    return result

def mileage(text):
    match=KM.search(text)
    if match:return int(re.sub(r"\D","",match.group(1)))
    raw=re.sub(r"[^\d]","",str(text))
    if raw.isdigit() and 1000<=int(raw)<=999999:return int(raw)
    return None

def safe_image(value):
    if not isinstance(value,str):return None
    if value.startswith("//"):value="https:"+value
    if value.startswith("http://"):value="https://"+value[7:]
    if "#" in value:return None
    p=urlparse(value)
    if p.scheme!="https" or not p.hostname:return None
    hosts=("marktplaats.com","marktplaats.nl","ebayimg.com")
    if not any(p.hostname==h or p.hostname.endswith("."+h) for h in hosts):return None
    return value[:1500]

def extract_images(obj):
    found=[]
    for x in (obj.get("pictures") or [])[:8]+(obj.get("imageUrls") or [])[:8]:
        if isinstance(x,dict):
            v=x.get("largeUrl") or x.get("mediumUrl") or x.get("url") or x.get("smallUrl")
        else:v=x
        v=safe_image(v)
        if v and v not in found:found.append(v)
    return found[:6]

def parse_listing(raw):
    title=str(raw.get("title") or "").strip()
    desc=str(raw.get("description") or "")
    price,kind=extract_price(raw)
    if price is None or price>COMP_MAX_PRICE or price<100:return None
    if BLOCK.search(title) or BLOCK.search(desc[:120]):return None
    attrs=flatten_attributes(raw)
    make,model=canonical_make_model(title,attrs)
    if not make or not model:return None
    year=None
    for val in [attrs.get("bouwjaar"),attrs.get("constructionyear"),attrs.get("year"),title,desc[:220]]:
        match=YEAR.search(str(val or ""))
        if match:year=int(match.group());break
    km=None
    for val in [attrs.get("kilometerstand"),attrs.get("mileage"),attrs.get("km-stand"),
                attrs.get("kilometers"),title,desc[:270]]:
        km=mileage(str(val or ""))
        if km is not None:break
    item=str(raw.get("itemId") or raw.get("item_id") or "").strip()
    path=str(raw.get("vipUrl") or raw.get("vip_url") or raw.get("url") or "")
    link=urljoin("https://www.marktplaats.nl/",path) if path else ("https://link.marktplaats.nl/"+item if item else "")
    if not link.startswith("https://") or "marktplaats.nl" not in urlparse(link).netloc:return None
    if not item:
        m=re.search(r"m\d{7,12}",link);item=m.group() if m else link
    loc=raw.get("location") or {}
    town=loc.get("cityName") or loc.get("city") or ""
    postcode=loc.get("postcode") or ""
    risktext=title+" "+desc[:350]
    damage=bool(DAMAGE.search(risktext))
    highrisk=bool(HIGH_RISK.search(risktext))
    # Extreem lage 'autoprijzen' zijn vaak placeholders, geen geverifieerde koopsommen.
    questionable_price=bool(price<300 or re.search(
        r'vanafprijs|advertentieprijs|richtprijs|aanbetaling|prijs\s+op\s+aanvraag',risktext,re.I))
    if re.search(r"\bgeen\s+schade\b",risktext,re.I) and not HIGH_RISK.search(risktext):damage=False
    pics=extract_images(raw)
    seller=raw.get("seller") or {}
    return {
        "id":"mp:"+item,"source":"Marktplaats","title":title,"description":desc[:170],
        "make":make.title(),"model":model.upper() if model.isdigit() else model.capitalize(),
        "year":year,"mileage":km,"price":price,"price_kind":kind,
        "fuel":attrs.get("brandstof") or attrs.get("fuel") or "",
        "transmission":attrs.get("transmissie") or attrs.get("transmission") or "",
        "city":town,"postcode":postcode,"url":link,
        "image_url":pics[0] if pics else None,"image_urls":pics[:2],
        "damage":damage,"high_risk":highrisk,
        "price_unverified":questionable_price,
        "price_warning":"Extreem lage vraagprijs of mogelijk placeholder; controleer werkelijke koopsom" if questionable_price else "",
        "damage_note":"Mogelijke ernstige technische schade; monteurcontrole nodig" if highrisk else
                                             "Schade/gebrek genoemd; reparatiekosten eerst bepalen" if damage else "",
        "seller_name":seller.get("sellerName") or "",
        "is_bid":kind!="FIXED",
    }

def get_page(session, *, query="", max_price=None, sort="SORT_INDEX", postcode="",radius_km=0,offset=0):
    params={"l1CategoryId":91,"limit":LIMIT,"offset":offset,
            "sortBy":sort,"sortOrder":"INCREASING" if sort=="PRICE" else "DECREASING"}
    if query:params["query"]=query
    if max_price is not None:params["attributeRanges[]"]=f"PriceCents:10000:{int(max_price)*100}"
    if postcode and radius_km>0:
        params["postcode"]=postcode
        params["distanceMeters"]=int(radius_km)*1000
    r=session.get(ENDPOINT,params=params,timeout=23)
    r.raise_for_status()
    data=r.json()
    if not isinstance(data,dict) or not isinstance(data.get("listings"),list):
        raise RuntimeError("Marktplaats-autogegevensstructuur gewijzigd")
    return [x for x in (parse_listing(y) for y in data["listings"] if isinstance(y,dict)) if x]

def get_market(session):
    """Goedkope auto's plus wat duurdere vergelijkingen, geen claim alle advertenties te dekken."""
    found={}; errors=[]; requests_done=0
    plans=[("",MAX_COLLECT_PRICE,"PRICE"),("",MAX_COLLECT_PRICE,"SORT_INDEX")]
    plans += [(q,MAX_COLLECT_PRICE,"PRICE") for q in SAMPLE_QUERIES]
    for query,price,sort in plans:
        try:
            rows=get_page(session,query=query,max_price=price,sort=sort)
            for x in rows:found[x["id"]]=x
        except (requests.RequestException,RuntimeError,ValueError) as exc:
            errors.append(f"Marktplaats {query or 'auto'}: {str(exc)[:90]}")
        requests_done+=1
        time.sleep(.25)
    # Vergelijkingsadvertenties worden apart opgehaald bij de meest voorkomende modellen.
    counts={}
    for d in found.values():
        if d["price"]<=MAX_COLLECT_PRICE and d.get("year"):
            key=(d["make"].lower(),d["model"].lower())
            counts[key]=counts.get(key,0)+1
    for make,model in sorted(counts,key=lambda k:(-counts[k],k))[:14]:
        try:
            rows=get_page(session,query=f"{make} {model}",max_price=COMP_MAX_PRICE,sort="PRICE")
            for x in rows:found[x["id"]]=x
        except (requests.RequestException,RuntimeError,ValueError) as exc:
            errors.append(f"Vergelijking {make} {model}: {str(exc)[:85]}")
        requests_done+=1
        time.sleep(.25)
    return list(found.values()),errors,requests_done

def comparables_for(item,all_items):
    if item.get("year") is None or item.get("mileage") is None:return []
    matches=[]
    for other in all_items:
        if other["id"]==item["id"] or other["make"]!=item["make"] or other["model"]!=item["model"]:
            continue
        if other.get("price_kind")!="FIXED" or other.get("damage") or other.get("high_risk") or other.get("price_unverified"):continue
        if other.get("year") is None or other.get("mileage") is None:continue
        if abs(other["year"]-item["year"])>2:continue
        allowed=max(50000,round(item["mileage"]*.28))
        if abs(other["mileage"]-item["mileage"])>allowed:continue
        matches.append(other)
    return matches

def value_deal(item,all_items,repair=250,other=175):
    matches=comparables_for(item,all_items)
    result=dict(item)
    result["comparables"]=len(matches)
    result["comparable_asking_prices"]=sorted([d["price"] for d in matches])[:5]
    result["resale_low"]=result["resale_high"]=None
    result["profit_low"]=result["profit_high"]=None
    result["profit_note"]="Geen betrouwbare doorverkoopschatting: te weinig vergelijkbare auto's met bouwjaar en km-stand."
    result["estimated_repair"]=repair
    result["estimated_other_costs"]=other
    result["total_investment"]=round(item["price"]+repair+other,2)
    result["pricing_confidence"]="geen"
    if len(matches)>=3:
        median=statistics.median(x["price"] for x in matches)
        # Vraagprijzen zijn geen gerealiseerde verkoopprijzen. 75–88% is hypothese.
        low=round(median*.75,2)
        high=round(median*.88,2)
        result.update(
            resale_low=low,resale_high=high,
            profit_low=round(low-result["total_investment"],2),
            profit_high=round(high-result["total_investment"],2),
            pricing_confidence="beperkt" if len(matches)<6 else "redelijk",
            profit_note=(f"Vraagprijsmediaan {len(matches)} vergelijkbare auto's, 75–88% "
                         f"als voorzichtig scenario; geen gerealiseerde verkoopprijzen. "
                         f"Reparatie €{repair:g} en overige kosten €{other:g} zijn aannames.")
        )
    return result

def sort_deals(items):
    """Hoogste conservatieve kans; onzekerheden komen na positieve onderbouwde marge."""
    for d in items:
        fixed=d.get("price_kind")=="FIXED"
        low=d.get("profit_low")
        compare=d.get("comparables") or 0
        if low is not None and low>0:
            score=200+min(low,1800)/12+min(compare,10)*2+(10 if fixed else -12)
            label="Sterke prijsindicatie" if fixed else "Interessant bod, niet gegarandeerd"
        elif d.get("profit_high") is not None and d["profit_high"]>0:
            score=90+min(d["profit_high"],1000)/50+(8 if fixed else 0)
            label="Mogelijke marge, onzeker"
        elif low is None:
            score=30+max(0,(5000-d["price"])/700)+(6 if fixed else 0)
            label="Niet voldoende vergelijkingsgegevens"
        else:
            score=10+min(max(d.get("profit_high") or -1000,-1000),0)/150
            label="Geen positieve onderbouwde marge"
        if d.get("high_risk"):score-=45
        elif d.get("damage"):score-=8
        if d.get("price_unverified"):score-=120;label="Prijs eerst controleren — mogelijk lokbedrag"
        d["deal_score"]=round(score,2)
        d["deal_label"]=label
    # Lokprijzen altijd onder geverifieerde vraagprijzen plaatsen, ook met positieve rekensom.
    items.sort(key=lambda x:(bool(x.get("price_unverified")),-x["deal_score"],x["price"],x["id"]))
    for i,d in enumerate(items):
        d["rank"]=i+1
        d["alert_candidate"]=(i<3 and d.get("profit_low") is not None and d["profit_low"]>0
                              and d.get("comparables",0)>=3 and not d.get("high_risk")
                              and d.get("price_kind")=="FIXED"
                              and not d.get("price_unverified"))
    return items

def shortlist(deals, max_items=430):
    """Beperk de publieke mobiele feed zodat de app snel synchroniseert.

    De bovenste 260 beste kansen blijven behouden. Vul aan met goedkope
    aanbiedingen over verschillende prijsklassen, voor instelbare filters.
    Deze steekproef is niet de volledige Marktplaats-markt.
    """
    if len(deals)<=max_items:return deals
    selected={}
    def put(x):
        if len(selected)<max_items:selected.setdefault(x["id"],x)
    # Bewaar expliciet ruimte voor echte Facebook-aanbiedingen.
    for x in deals[:220]:put(x)
    for x in (d for d in deals if d.get("source")=="Facebook Marketplace"):
        if sum(1 for row in selected.values() if row.get("source")=="Facebook Marketplace")>=90:break
        put(x)
    bands=[(100,500),(500,1000),(1000,1500),(1500,2500),(2500,5000)]
    for lo,hi in bands:
        subset=sorted((x for x in deals if lo<=x["price"]<hi),
                      key=lambda x:(x["price"],-x["deal_score"]))
        for d in subset[:26]:put(d)
    for d in (x for x in deals if x["damage"] and not x["high_risk"]):
        put(d)
    for d in deals:put(d)
    return sorted(selected.values(),
                  key=lambda d:(bool(d.get("price_unverified")),-d["deal_score"],d["price"],d["id"]))

def previous_feed():
    if not OUTPUT.exists():return {}
    try:
        previous=json.loads(OUTPUT.read_text(encoding="utf8"))
        return previous if isinstance(previous,dict) else {}
    except (ValueError,OSError):return {}


def facebook_entries(previous, session, current_hour):
    """Fetch Facebook on 4 UTC hours daily and reuse at most 24h of results."""
    from facebook_marketplace import fetch as fetch_fb
    token=bool(os.getenv("APIFY_TOKEN","").strip())
    if not token:
        return [],{"status":"niet_geconfigureerd",
                   "count":0,
                   "info":"APIFY_TOKEN ontbreekt; geen Facebook-advertenties verzonnen"}
    prior_status=previous.get("facebook_status") or {}
    last_checked=str(prior_status.get("checked_at") or "")
    previous_fb=[d for d in (previous.get("deals") or [])
                 if isinstance(d,dict) and d.get("source")=="Facebook Marketplace"]
    still_recent=False
    if last_checked:
        try:
            last=datetime.fromisoformat(last_checked.replace("Z","+00:00"))
            still_recent=0<=(datetime.now(timezone.utc)-last).total_seconds()<24*3600
        except (ValueError,TypeError):pass

    should_scan=(os.getenv("AUTOVINDER_FB_FORCE","")=="1"
                 or not still_recent
                 or current_hour in (0,6,12,18))
    if should_scan:
        listings,status=fetch_fb(session)
        if status.get("status") in ("ok","geen_passende_advertenties"):
            return listings,status
        status["cached_results"]=len(previous_fb) if still_recent else 0
        return (previous_fb if still_recent else []),status
    prior_status=dict(prior_status)
    prior_status["status"]="bewaarde_resultaten"
    prior_status["count"]=len(previous_fb)
    return previous_fb,prior_status


def run(session=None):
    session=session or requests.Session()
    session.headers.update(HEADERS)
    now=datetime.now(timezone.utc).isoformat(timespec="seconds")
    previous=previous_feed()
    errors=[]
    try:
        all_items,errors,reqs=get_market(session)
        mp_candidates=[d for d in all_items if d["price"]<=MAX_COLLECT_PRICE]
    except (requests.RequestException,RuntimeError,ValueError) as ex:
        errors.append("Marktplaats-bron: "+str(ex))
        all_items=[];mp_candidates=[];reqs=0
    facebook,fb_status=facebook_entries(previous,session,datetime.now(timezone.utc).hour)
    if fb_status.get("status") in ("fout","toegang_geweigerd"):
        errors.append("Facebook: "+fb_status.get("error","Provider tijdelijk niet beschikbaar"))
    # Facebook-advertenties krijgen dezelfde conservatieve waardering als
    # Marktplaats-auto's, op basis van Marktplaats-vraagprijzen van hetzelfde model.
    comparison_data=[d for d in all_items if d.get("source")=="Marktplaats"]
    collected={d["id"]:d for d in mp_candidates+facebook if d.get("id")}
    ranked=sort_deals([value_deal(d,comparison_data) for d in collected.values()])
    deals=shortlist(ranked)
    if not deals:
        previous_deals=previous.get("deals") or []
        status="FOUT"
        deals=previous_deals
        stale=bool(previous_deals)
    else:
        status="GEDEELTELIJK" if errors else "OK"
        stale=False
    data={"generated_at":now,"source_status":status,"stale":stale,
          "errors":errors,"deals":deals,"requests_done":reqs,
          "facebook_status":fb_status,
          "source_counts":{"Marktplaats":sum(d["source"]=="Marktplaats" for d in deals),
                           "Facebook Marketplace":sum(d["source"]=="Facebook Marketplace" for d in deals)},
          "default_filters":DEFAULT_FILTERS,
          "source_info":{
              "Marktplaats":"automatisch; websitezoekendpoint zonder garantie",
              "Facebook Marketplace":("automatisch via Apify, externe provider met mogelijke kosten"
                    if os.getenv("APIFY_TOKEN","") else
                    "automatische koppeling klaar, maar APIFY_TOKEN nog niet ingesteld")},
          "valuation_notice":"75–88% van mediaan van actieve vergelijkbare vraagprijzen; geen gerealiseerde verkoopprijs. "
                             "Reparatie en andere kosten zijn instelbare aannames."}
    OUTPUT.parent.mkdir(parents=True,exist_ok=True)
    tmp=OUTPUT.with_suffix(".tmp")
    tmp.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding="utf8")
    tmp.replace(OUTPUT)
    return data

if __name__=="__main__":
    result=run()
    print("AutoVinder:",len(result["deals"]),"auto's",result["source_status"],
          "bronfouten:",result["errors"][:3])
    if result["source_status"]=="FOUT":raise SystemExit(1)
