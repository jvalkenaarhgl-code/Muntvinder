"""Gratis ontdekking van publiek geïndexeerde Facebook Marketplace-autoadvertenties.

Zoekt uitsluitend via een vrij beschikbare zoekmachine-RSS-feed. Maakt GEEN
geautomatiseerde verzoeken naar Facebook, gebruikt geen cookies, proxy's,
CAPTCHA-bypasses, betaalde API's of persoonlijke inloggegevens.

Zoekresultaten zijn fragmenten: onvolledig en soms verouderd. Vermelde prijzen
zijn ONGEVERIFIEERD, dus nooit als zekere koopprijs of pushwaardige deal tonen.
"""
from __future__ import annotations
import html
import os
import re
from datetime import datetime, timezone
from urllib.parse import urlparse
from xml.etree import ElementTree as ET
import requests

SEARCH_URL="https://www.bing.com/search"
QUERIES=[
    'site:facebook.com/marketplace/item/ Opel Corsa Nederland auto',
    'site:facebook.com/marketplace/item/ Peugeot 107 Nederland',
    'site:facebook.com/marketplace/item/ Toyota Aygo Nederland',
    'site:facebook.com/marketplace/item/ Ford Fiesta Nederland',
    'site:facebook.com/marketplace/item/ Volkswagen Polo Nederland',
    'site:facebook.com/marketplace/item/ Renault Clio Nederland',
    'site:facebook.com/marketplace/item/ auto Overijssel',
    'site:facebook.com/marketplace/item/ auto Rijssen Zwolle',
]
PRICE_RE=re.compile(r'(?:€\s*|EUR\s*)(\d{1,5}(?:[.\s]\d{3})*(?:,\d{2})?)',re.I)
ITEM_RE=re.compile(r'/marketplace/item/(\d{6,25})/?',re.I)
YEAR_RE=re.compile(r'(?<!\d)(?:19[89]\d|20[0-2]\d)(?!\d)')
KM_RE=re.compile(r'(?<!\d)(\d{1,3}(?:[.\s]\d{3})+|\d{4,6})\s*(?:km\b|kilometer)',re.I)

def plain(raw):
    return re.sub(r'<[^>]*>',' ',html.unescape(str(raw or ''))).strip()

def amount(text):
    m=PRICE_RE.search(text)
    if not m:return None
    raw=m.group(1).replace(' ','').replace('.','').replace(',','.')
    try: price=round(float(raw),2)
    except (ValueError,TypeError):return None
    return price if 100<=price<=5000 else None

def normalize_result(item):
    """Alleen echte marketplace/item-links met herkenbare auto en vraagprijs."""
    title=plain(item.get('title'))
    snippet=plain(item.get('description'))
    link=str(item.get('link') or '')
    parsed=urlparse(link)
    domain=(parsed.hostname or '').lower()
    match=ITEM_RE.search(parsed.path)
    if parsed.scheme!='https' or not (
        domain=='facebook.com' or domain.endswith('.facebook.com')
    ) or not match:
        return None
    from scanner import (canonical_make_model, DAMAGE,HIGH_RISK,BLOCK)
    make,model=canonical_make_model(title)
    if not make or not model or BLOCK.search(title+' '+snippet[:180]):
        return None
    price=amount(title) or amount(snippet)
    if price is None:return None
    text=title+' '+snippet
    matchyear=YEAR_RE.search(text)
    year=int(matchyear.group()) if matchyear else None
    matchkm=KM_RE.search(text)
    km=int(re.sub(r'\D','',matchkm.group(1))) if matchkm else None
    risk=bool(DAMAGE.search(text))
    highrisk=bool(HIGH_RISK.search(text))
    return {
        'id':'fb:'+match.group(1),'source':'Facebook Marketplace',
        'title':title[:180],'description':snippet[:260],
        'make':make.title(),'model':model.upper() if model.isdigit() else model.capitalize(),
        'year':year,'mileage':km,'price':price,'price_kind':'FIXED',
        'fuel':'','transmission':'','city':'','postcode':'',
        'url':'https://www.facebook.com/marketplace/item/'+match.group(1)+'/',
        'image_url':None,'image_urls':[],
        'damage':risk,'high_risk':highrisk,
        'damage_note':'Controleer technische gebreken' if risk else '',
        'seller_name':'',
        'price_unverified':True,
        'price_warning':'Zoekmachinesnippet: prijs en beschikbaarheid niet geverifieerd',
        'is_bid':False,'facebook_discovery':'publieke_zoekindex',
        'facebook_checked_at':datetime.now(timezone.utc).isoformat(timespec='seconds')
    }

def fetch(session=None):
    """Gratis RSS-zoekopdrachten, maximaal acht, nooit rechtstreeks Facebook crawlen."""
    session=session or requests.Session()
    found={}
    errors=[]
    for query in QUERIES:
        try:
            response=session.get(SEARCH_URL,params={
                'q':query,'format':'rss','setlang':'nl-NL'
            },headers={'User-Agent':'AutoVinder/1.2 (+public-search-index)',
                       'Accept':'application/rss+xml, application/xml, text/xml'},
                timeout=16)
            response.raise_for_status()
            if len(response.content)>700_000:
                raise ValueError('Te grote zoekresultaatfeed')
            document=ET.fromstring(response.content)
            for item in document.findall('.//item'):
                row=normalize_result({
                    'title':item.findtext('title'),
                    'description':item.findtext('description'),
                    'link':item.findtext('link')
                })
                if row:found[row['id']]=row
        except (requests.RequestException,ET.ParseError,ValueError) as exc:
            errors.append(type(exc).__name__)
    state=('ok' if found else 'fout' if len(errors)==len(QUERIES)
           else 'geen_passende_advertenties')
    return list(found.values()),{
        'status':state,'count':len(found),
        'checked_at':datetime.now(timezone.utc).isoformat(timespec='seconds'),
        'provider':'gratis openbare zoekmachine-index, geen Facebook-API',
        'queries':len(QUERIES),'failed_queries':len(errors),
        'note':'Resultaten zijn mogelijk oud/onvolledig; prijzen en voorraad ongeverifieerd',
        'error':'Zoekindex tijdelijk niet bereikbaar' if state=='fout' else ''
    }
