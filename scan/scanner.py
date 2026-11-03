"""Muntvinder v2: Nederlandse proofset-kansen. Publiceert uitsluitend geobserveerde aanbiedingen.

Bronnen: Marktplaats openbare zoekoppervlakte (niet-officiele endpoint, kan veranderen)
          Catawiki openbare categorie en objectpagina's (alleen bundels).
Pas het scaninterval aan en volg de gebruiksvoorwaarden van de platforms.
"""
from __future__ import annotations

import json
import os
import re
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup
from shop_prices import load_shop_prices, match_shops, refine_resale_with_shops

HERE = Path(__file__).resolve().parent.parent
DEST = HERE / 'docs' / 'feed.json'
PREVIOUS = DEST
MP_ENDPOINT = 'https://www.marktplaats.nl/lrp/api/search'
CW_CATEGORIES = ['https://www.catawiki.com/nl/x/547301-proefset-nederland-wereldmunten',
                 'https://www.catawiki.com/nl/x/307761-proefset']
QUERY_TERMS = ['proofset nederland', 'proofjaarset nederland', 'proof set nederland', 'knm proofjaarset']
HEADERS = {'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
           'Accept': 'application/json, text/html;q=0.9,*/*;q=0.8', 'Accept-Language': 'nl-NL,nl;q=0.9',
           'Referer': 'https://www.marktplaats.nl/'}
YEAR = re.compile(r'(?<!\d)(19[5-9]\d|20[0-2]\d)(?!\d)')
PROOF = re.compile(r'\b(proof\s*sets?|proofjaarsets?|proof\s*jaarsets?|proefsets?|jaarsets?\s+proof)\b',re.I)
NETHERLANDS = re.compile(r'\b(nederland(?:se)?|netherlands|holland|knm)\b',re.I)
BUNDLE = re.compile(r'\b(bundels?|partijen?|partijtjes?|collectie(?:s)?|combinatie|meerdere|diverse\s+(?:jaren|sets?|proofsets?)|sets?\s*\(?\d{1,2}\b|\d{1,2}\s*(?:stuks|sets?|jaarsets?|proofsets?|verschillende)|serie(?:s|n))\b',re.I)
SPECIAL = re.compile(r'\b(transitie(?:set)?|transition|tokyo|geesink|gekleurde|colored|jubileum|special(?:e)?|kleurset|goodedoelenset|goede doelen)\b',re.I)
EXCLUDED = re.compile(r'\b(niet\s+proof|bu\.?(?:nc)?\s+set|miniatuur|replica|losse\s+munt|alleen\s+de\s+doos|leeg\s+album|fiji|vatican|vaticaan|belgie|belgië|frankrijk|finland|san marino)\b',re.I)

class SourceFailure(RuntimeError):
    pass

def years_from(text: str) -> list[int]:
    ys = sorted({int(n) for n in YEAR.findall(text)})
    # 2022/2024 (3 sets) duidt op een reeks, niet automatisch op 2 sets.
    m = re.search(r'(?<!\d)((?:19[5-9]|20[0-2])\d)\s*[/\u2013-]\s*((?:19[5-9]|20[0-2])\d)\s*\(?(\d+)\s*(?:sets?|stuks|verschillende)', text, re.I)
    if m and 1 < int(m[3]) <= 20 and int(m[2])-int(m[1])+1 == int(m[3]):
        ys = sorted(set(ys) | set(range(int(m[1]),int(m[2])+1)))
    return ys

def identify(title: str, desc: str = '') -> dict | None:
    combined = title + ' ' + desc[:350]
    if not PROOF.search(combined) or EXCLUDED.search(title): return None
    # Catawiki heeft vaak 'Nederland.' in de titel. Geen vreemde muntsets toelaten.
    if not NETHERLANDS.search(combined): return None
    ys = years_from(title) or years_from(desc[:350])
    if not ys: return None
    special = bool(SPECIAL.search(title))
    bundle = bool(BUNDLE.search(title)) or len(ys) > 1
    m = re.search(r'\b(\d{1,3})\s*(?:proofsets?|sets?|jaarsets?)\b', title, re.I)
    count = max(len(ys), int(m[1]) if m else 1)
    bundle = bundle or count > 1
    typ = 'special' if special else ('gulden' if max(ys) <= 2001 else 'euro' if min(ys) >= 2002 else 'gemengd')
    return {'years': ys, 'set_type': typ, 'bundle': bundle, 'count': count}

def mp_price(obj: dict):
    info = obj.get('priceInfo') or {}
    if not info and ('price_cents' in obj or 'price' in obj):
        info = {'priceCents': obj.get('price_cents'), 'priceType': obj.get('price_type')}
        if info['priceCents'] is None and isinstance(obj.get('price'),(int,float)):
            info['priceCents'] = round(obj['price']*100)
    kind = str(info.get('priceType') or '').upper()
    try:
        cents = int(info.get('priceCents'))
        if cents <= 0: return None, kind
        return round(cents/100,2),kind
    except (ValueError,TypeError): return None,kind

def clean_image_url(value: str) -> str | None:
    """Alleen originele HTTPS-foto's van bekende Marktplaats/Catawiki-CDN's."""
    if not isinstance(value, str):
        return None
    value = value.strip()
    if value.startswith('//'):
        value = 'https:' + value
    if value.startswith('http://'):
        value = 'https://' + value[len('http://'):]
    if '#' in value:  # Niet-geïnstantieerde Marktplaats thumbnailtemplate
        return None
    parsed = urlparse(value)
    host = (parsed.hostname or '').lower()
    allowed = ('marktplaats.com', 'marktplaats.nl',
               'catawiki.com', 'catawiki.net', 'catawiki.nl', 'ebayimg.com')
    if parsed.scheme != 'https' or not any(host == d or host.endswith('.'+d) for d in allowed):
        return None
    return value if len(value) <= 1500 else None


def mp_images(obj: dict) -> list[str]:
    """De niet-officiële zoek-API levert pictures[] én imageUrls[]; beide ondersteunen."""
    result = []
    for item in (obj.get('pictures') or [])[:12]:
        if isinstance(item, str):
            candidates = [item]
        elif isinstance(item, dict):
            candidates = [item.get(k) for k in ('largeUrl','mediumUrl','extraExtraLargeUrl',
                                                  'smallUrl','extraSmallUrl','url')]
        else:
            continue
        for candidate in candidates:
            u = clean_image_url(candidate)
            if u:
                if u not in result:
                    result.append(u)
                break
    for item in (obj.get('imageUrls') or [])[:12]:
        if isinstance(item, dict):
            item = item.get('url') or item.get('mediumUrl')
        u = clean_image_url(item)
        if u and u not in result:
            result.append(u)
    return result[:8]


def mp_listing(obj: dict) -> dict | None:
    title = str(obj.get('title') or '')
    desc = str(obj.get('description') or '')
    info = identify(title, desc)
    if not info: return None
    price, kind = mp_price(obj)
    item = str(obj.get('itemId') or obj.get('item_id') or '').strip()
    path = str(obj.get('vipUrl') or obj.get('vip_url') or obj.get('url') or '')
    url = urljoin('https://www.marktplaats.nl/',path) if path else ('https://link.marktplaats.nl/'+item if item else '')
    if not url.startswith('https://') or 'marktplaats.nl' not in url: return None
    # Externe veilingadvertenties met /a123... zijn geen normale Marktplaats-koopadvertenties.
    if re.search(r'/a\d{7,}',url) or re.search(r'zonder\s+minimumprijs|geen\s+minimumprijs',title,re.I): return None
    if not item:
        match = re.search(r'm\d{7,12}',url)
        item = match[0] if match else url
    return {'id':'mp:'+item,'source':'Marktplaats','title':title,'description':desc[:500],
            **info, 'price':price, 'price_kind':kind,'url':url,
            'image_urls':mp_images(obj), 'image_url':next(iter(mp_images(obj)), None),
            'price_note':'Vraagprijs' if kind == 'FIXED' else 'Vraag-/biedprijs: controleren' if price is not None else 'Prijs onbekend'}

def fetch_mp(session: requests.Session) -> list[dict]:
    """Twee zoekrichtingen: nieuwste advertenties en goedkoopste advertenties.

    Er is geen officiële afspraak dat de publieke website-API beschikbaar blijft;
    een gewijzigde respons wordt nadrukkelijk als bronfout gemeld.
    """
    found = {}
    failures = []
    for q in QUERY_TERMS:
        for sort_by, sort_order in [('SORT_INDEX', 'DECREASING'), ('PRICE', 'INCREASING')]:
            params = {'query': q, 'limit': 100, 'offset': 0,
                      'sortBy': sort_by, 'sortOrder': sort_order}
            try:
                r = session.get(MP_ENDPOINT, params=params, timeout=25)
                if r.status_code != 200:
                    raise SourceFailure(f'HTTP {r.status_code}')
                parsed = r.json()
                if not isinstance(parsed, dict) or not isinstance(parsed.get('listings'), list):
                    raise SourceFailure('antwoordstructuur gewijzigd')
                for raw in parsed['listings']:
                    if not isinstance(raw, dict): continue
                    item = mp_listing(raw)
                    if item: found[item['id']] = item
            except (ValueError, requests.RequestException, SourceFailure) as ex:
                failures.append(f'{q} ({sort_by}): {ex}')
            time.sleep(1)
    if not found and failures:
        raise SourceFailure('Marktplaats ophalen mislukt: ' + '; '.join(failures[:2]))
    return list(found.values())

def parse_catawiki_catalog(html: str, base_url: str):
    soup = BeautifulSoup(html, 'html.parser')
    seen = set()
    for a in soup.select('a[href]'):
        url = urljoin(base_url, a.get('href',''))
        if not re.search(r'/nl/l/\d+-',url): continue
        if url in seen: continue
        title = ' '.join(a.stripped_strings)
        if not title or not identify(title): continue
        info = identify(title)
        if not info or not info['bundle']: continue
        seen.add(url)
        yield {'id':'cw:'+re.search(r'/l/(\d+)',url)[1], 'source':'Catawiki',
               'title':title, 'description':'',**info,'price':None,'price_kind':'BID',
               'url':url, 'image_urls':[], 'image_url':None,
               'price_note':'Huidig bod nog niet geverifieerd'}

def parse_catawiki_lot(html: str, listing: dict) -> dict:
    soup = BeautifulSoup(html,'html.parser')
    text = soup.get_text('\n',strip=True)
    thumb = soup.select_one('meta[property="og:image"][content], meta[name="twitter:image"][content]')
    image = clean_image_url(thumb.get('content','')) if thumb else None
    if image:
        listing['image_url'] = image
        listing['image_urls'] = [image]
    # 'Startbod €1' is geen 'Huidig bod'. Toon pas een bedrag bij expliciet huidig bod.
    m = re.search(r'Huidig\s+bod\s*\n?\s*€\s*([\d.,]+)',text,re.I)
    if m:
        value = m.group(1).replace('.','').replace(',','.')
        try:
            listing['price'] = float(value)
            listing['price_note'] = 'Huidig bod, kan stijgen tot veilingeinde'
        except ValueError: pass
    elif re.search(r'Geen biedingen uitgebracht',text,re.I):
        listing['price_note'] = 'Nog geen biedingen (startbod telt niet als marktprijs)'
    # Prijs voor Nederland kan dynamisch of land-afhankelijk zijn; niet gokken.
    return listing

def fetch_cw(session: requests.Session) -> list[dict]:
    seen = {}
    for url in CW_CATEGORIES:
        r = session.get(url,timeout=25)
        if r.status_code != 200: raise SourceFailure(f'Catawiki HTTP {r.status_code}')
        for item in parse_catawiki_catalog(r.text,url):
            seen[item['id']] = item
        time.sleep(1.5)
    for item in seen.values():
        try:
            r = session.get(item['url'],timeout=20)
            if r.ok: parse_catawiki_lot(r.text,item)
        except requests.RequestException: pass
        time.sleep(1)
    return list(seen.values())

def calc_market(deals: list[dict]) -> list[dict]:
    # Alleen VASTE Marktplaats-vraagprijzen als vergelijkingsbasis, geen biedingen of Catawiki.
    comparables = {}
    for d in deals:
        if d['source']!='Marktplaats' or d['price_kind']!='FIXED' or d['price'] is None:continue
        if d['bundle'] or d['set_type']=='special':continue
        if len(d['years'])!=1:continue
        comparables.setdefault((d['years'][0],d['set_type']),[]).append(d)

    def estimate(year, typ, own_id):
        prices = [x['price'] for x in comparables.get((year,typ),[]) if x['id'] != own_id]
        if len(prices)<1: return None
        median = statistics.median(prices)
        # 80%-95% zijn UITDRUKKELIJK aannames op basis van actuele vraagprijzen,
        # geen bewezen verkoopprijzen. Wordt in UI duidelijk benoemd.
        return round(median*.80,2),round(median*.95,2),len(prices)

    for d in deals:
        refs = []
        if d['set_type'] not in ('gemengd','special') and d['count']==len(d['years']):
            for y in d['years']:
                e = estimate(y,'gulden' if y<=2001 else 'euro',d['id'])
                if e is None: refs = [];break
                refs.append(e)
        d['comparison_count'] = min((r[2] for r in refs),default=0)
        d['resale_low'] = round(sum(r[0] for r in refs),2) if refs else None
        d['resale_high'] = round(sum(r[1] for r in refs),2) if refs else None
        d['resale_note'] = (('Zeer onzekere indicatie: slechts %d vergelijkbare vaste vraagprijs(en) per set. 80–95%% van de mediane vraagprijs; geen gerealiseerde verkoopprijs.' % d['comparison_count'])
                            if refs and d['comparison_count']<3 else
                            'Indicatie: 80–95% van mediane actieve Marktplaats-vraagprijzen. Geen gerealiseerde verkoopprijzen.'
                            if refs else 'Geen voldoende gelijkwaardige advertenties voor prijsindicatie')
        price = d['price']
        if price is None: d['purchase_total'] = None
        elif d['source']=='Catawiki':
            # 9% + €3 is Catawiki-vergoeding, €12 is een instelbare VERZENDaanname.
            shipping = float(os.getenv('CW_SHIPPING_ESTIMATE','12'))
            d['purchase_total'] = round(price*1.09+3+shipping,2)
            d['purchase_note'] = f'Huidig bod + 9% + €3, verzending aangenomen €{shipping:g}; definitief bod/verzending kan hoger zijn'
        else:
            shipping = float(os.getenv('MP_SHIPPING_ESTIMATE','0'))
            d['purchase_total'] = round(price+shipping,2)
            d['purchase_note'] = f'Vraag-/biedprijs + aangenomen €{shipping:g} verzending; onderhandelbaar'
        if d['resale_low'] is not None and d['purchase_total'] is not None:
            d['profit_low'] = round(d['resale_low']-d['purchase_total'],2)
            d['profit_high'] = round(d['resale_high']-d['purchase_total'],2)
            d['discount_ratio'] = round(1-d['purchase_total']/max(d['resale_high'],1),4)
        else:
            d['profit_low'] = d['profit_high'] = d['discount_ratio'] = None
        # Geen harde minimale winst of ROI-drempel.
        # Prioriteit eerst op afgeprijsd ten opzichte van vergelijkbare sets; rest op laagste aankoopprijs.
        d['rank'] = round(d['discount_ratio'],4) if d['discount_ratio'] is not None else None
    return sorted(deals,key=lambda x: (
        0 if x['rank'] is not None else 1,
        -(x['rank'] if x['rank'] is not None else -9),
        x['purchase_total'] if x['purchase_total'] is not None else 1e10))

def run(session=None):
    session = session or requests.Session()
    session.headers.update(HEADERS)
    errors = []
    deals = {}
    for name,collector in [('Marktplaats',fetch_mp),('Catawiki',fetch_cw)]:
        try:
            for d in collector(session): deals[d['id']]=d
        except (SourceFailure,requests.RequestException,ValueError,KeyError) as err:
            errors.append(f'{name}: {err}')
    now = datetime.now(timezone.utc).isoformat(timespec='seconds')
    if not deals:
        old = None
        if PREVIOUS.exists():
            try:old=json.loads(PREVIOUS.read_text(encoding='utf8'))
            except (ValueError,OSError):pass
        # Geen nep- of lege 'live scan' bij beide bronnen uitval.
        existing = old.get('deals',[]) if isinstance(old,dict) else []
        output={'generated_at':now,'deals':existing,'stale':bool(existing),
                'errors':errors or ['Geen aanbiedingen opgehaald'], 'source_status':'FOUT'}
    else:
        assessed = calc_market(list(deals.values()))
        try:
            catalog = load_shop_prices(session)
            match_shops(assessed, catalog)
            shop_status = {
                'checked_at': catalog.get('checked_at'),
                'shops': catalog.get('checked_shops', []),
                'errors': catalog.get('errors', []),
                'outdated': catalog.get('outdated', False),
                'number_of_prices': len(catalog.get('offers', [])),
            }
        except (OSError, ValueError, requests.RequestException, RuntimeError) as err:
            shop_status = {'checked_at': None, 'shops': [],
                           'errors': [f'Winkelvergelijking tijdelijk niet beschikbaar: {err}'],
                           'number_of_prices': 0}
            match_shops(assessed, {})
        assessed = refine_resale_with_shops(assessed)
        output={'generated_at':now,'deals':assessed,'stale':False,
                'errors':errors,'source_status':'GEDEELTELIJK' if errors else 'OK',
                'shop_status':shop_status}
    DEST.parent.mkdir(parents=True,exist_ok=True)
    temp=DEST.with_suffix('.tmp')
    temp.write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf8')
    temp.replace(DEST)
    return output

if __name__=='__main__':
    data=run()
    print(f"Muntvinder: {len(data['deals'])} aanbiedingen, status {data['source_status']}, fouten: {data['errors']}")
    if data['source_status']=='FOUT':sys.exit(1)
