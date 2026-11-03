"""Controleerbare winkelprijsvergelijking voor complete Nederlandse proofsets.

Alleen letterlijk aangetroffen productprijzen uit een beperkt aantal Nederlandse muntenwebshops.
Geen zoekresultaat-rangschikkingen, verkoopbewijzen of algemene 'marktwaarde'.
"""
from __future__ import annotations

import json
import os
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / 'docs' / 'shop_prices.json'
CACHE_SECONDS = 6 * 3600
CACHE_SCHEMA = 4

SHOPS = [
    ('Bildtmunt', 'https://www.bildtmunt.com/nederlandse-munten-knm/proof-sets-nederland'),
    ('De Amsterdamse Munt', 'https://www.deamsterdamsemunt.nl/nederlandse-munten/muntsets/jaarsets-proof/?page=all'),
    ('Hansmunt', 'https://hansmunt.nl/product-categorie/euromunten/euromunten-nederland/nederland-proof-sets/'),
    ('Hansmunt', 'https://hansmunt.nl/product-categorie/euromunten/euromunten-nederland/nederland-proof-sets/page/2/'),
    ('Theo Peters', 'https://www.theopeters.com/moderne-munten/muntsets/proofsets-euromunten/'),
]
YEAR_RE = re.compile(r'(?<!\d)(?:19[5-9]\d|20[0-2]\d)(?!\d)')
MONEY_RE = re.compile(r'€\s*(\d{1,5}(?:[.,]\d{2})?)')
PROOFSET_RE = re.compile(r'proof\s*jaarset|proofjaarset|proof\s*set|proofset|jaarset.{0,16}proof', re.I)
REJECT_RE = re.compile(
    r'proof\s*like|prooflike|unc\b|\bbu\b|muntmeester|transitie|combinatie|'
    r'combi\b|zonder\s+(?:omhoes|cassette|verpakking|doos)|alle\s+\d+\s+munten\s+in\s+capsule|'
    r'losse?\s+munten|set\s+in\s+capsules|replica|kleurset|gekleurde|'
    r'\bcomplete\s+proofsets\b|\bserie\s+(?:van|nederland)\b', re.I
)
SOLD_OUT_RE = re.compile(
    r'uitverkocht|sold\s*out|out\s*of\s*stock|'
    r'melding\s+bij\s+beschikbaarheid|houd\s+mij\s+op\s+de\s+hoogte|'
    r'niet\s+op\s+voorraad|not\s+in\s+stock', re.I
)
STOCK_RE = re.compile(
    r'in\s+winkelwagen|op\s+voorraad|add\s+to\s+cart|bestellen|'
    r'toevoegen\s+aan\s+winkelwagen|in\s+stock', re.I
)


def exact_year(title: str) -> int | None:
    clean = ' '.join(title.split())
    if not PROOFSET_RE.search(clean) or REJECT_RE.search(clean):
        return None
    years = YEAR_RE.findall(clean)
    if len(years) != 1:
        return None
    year = int(years[0])
    # Euromunten 1999–2001 zijn geen gewone guldenproofsets.
    # De scanner noemt deze vroege jaren gulden; match nooit een euromuntenserie.
    if year < 2002 and not re.search(r'gulden|guldens', clean, re.I):
        return None
    return year


def parse_euros(text: str) -> float | None:
    match = MONEY_RE.search(text)
    if not match:
        return None
    raw = match.group(1)
    if raw.count(',') == 1:
        raw = raw.replace('.', '').replace(',', '.')
    elif raw.count('.') == 1 and len(raw.split('.')[-1]) != 2:
        return None
    try:
        price = float(raw)
    except ValueError:
        return None
    return round(price, 2) if 1 <= price <= 5000 else None


def pick_card(anchor):
    """Zoek de productkaart, nooit een volledige catalogus met meerdere sets."""
    chosen = None
    for el in [anchor, *list(anchor.parents)[:12]]:
        if el.name in ('body', 'html', 'section', 'main', 'ul', 'ol'):
            break
        full_text = el.get_text(' ', strip=True)
        if len(full_text) > 2200 or len(el.select('a[href]')) > 9:
            break
        # Beschrijvingen met andere jaren behoren tot een andere productkaart.
        other_titles = [
            a for a in el.select('a[href]')
            if exact_year(' '.join(a.stripped_strings) or a.get('title','')) is not None
            and urljoin('https://example.com/',a.get('href','')) != urljoin('https://example.com/',anchor.get('href',''))
        ]
        if other_titles:
            break
        if MONEY_RE.search(full_text):
            chosen = el
    return chosen


def parse_catalog(html: str, name: str, url: str) -> list[dict]:
    soup = BeautifulSoup(html, 'html.parser')
    offers = {}
    for anchor in soup.select('a[href]'):
        # Producttitels kunnen in het label of in het titelattribuut staan.
        label = ' '.join(anchor.stripped_strings).strip()
        if not label:
            label = (anchor.get('title') or anchor.get('aria-label') or '').strip()
        year = exact_year(label)
        if year is None:
            continue
        href = urljoin(url, anchor.get('href', ''))
        if not href.startswith('https://') or urlparse(href).netloc.lower().removeprefix('www.') != urlparse(url).netloc.lower().removeprefix('www.'):
            continue
        card = pick_card(anchor)
        if card is None:
            continue
        card_text = card.get_text(' ', strip=True)
        # Voorraadstatus van zo klein mogelijke productkaart, niet het hele assortiment.
        if SOLD_OUT_RE.search(card_text):
            continue
        amount_node = card.select_one('.price ins, ins .amount, .price .sale-price, .price .amount, .product-price, .price, [itemprop="price"]')
        price = parse_euros(amount_node.get_text(' ', strip=True)) if amount_node else None
        if price is None:
            # Verwijder titels vóór we naar een prijs zoeken; '2€ 200 Jaar Nederland'
            # is de naam van een herdenkingsmunt, NIET de webshopprijs!
            copy = BeautifulSoup(str(card), 'html.parser')
            for link in copy.select('a'):
                link.decompose()
            price = parse_euros(copy.get_text(' ', strip=True))
        if price is None:
            continue
        is_stock_confirmed = bool(STOCK_RE.search(card_text))
        offer = {
            'year': year, 'shop': name, 'price': price, 'title': label,
            'url': href, 'availability': 'beschikbaar' if is_stock_confirmed else 'onbevestigd',
        }
        # Als dezelfde winkel twee aanbiedingen heeft, gaat de goedkoopste complete set voor.
        key = (name, year)
        if key not in offers or offers[key]['price'] > price:
            offers[key] = offer
    return list(offers.values())


def cached_shops(max_age: int = CACHE_SECONDS) -> dict | None:
    if not CACHE.exists():
        return None
    try:
        doc = json.loads(CACHE.read_text(encoding='utf-8'))
        if doc.get('schema_version') != CACHE_SCHEMA:
            return None
        timestamp = datetime.fromisoformat(doc['checked_at'])
        age = (datetime.now(timezone.utc) - timestamp).total_seconds()
        if 0 <= age <= max_age and isinstance(doc.get('offers'), list):
            return doc
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return None


def load_shop_prices(session: requests.Session) -> dict:
    """Haal maximaal 5 categoriepagina's op, hergebruik resultaten 6 uur."""
    cached = cached_shops()
    if cached is not None:
        return cached
    offers, errors, reachable = {}, [], set()
    session_headers = {'User-Agent': 'Mozilla/5.0 (compatible; Muntvinder/2.2; proofset-prijsvergelijking)',
                       'Accept-Language': 'nl-NL,nl;q=0.9',
                       'Accept': 'text/html,application/xhtml+xml;q=0.9,*/*;q=0.8'}
    for name, url in SHOPS:
        try:
            response = session.get(url, timeout=18, headers=session_headers)
            if response.status_code != 200:
                raise RuntimeError(f'HTTP {response.status_code}')
            parsed = parse_catalog(response.text, name, url)
            print(f'Winkelvergelijking {name}: {len(parsed)} verifieerbare productvermeldingen')
            if not parsed:
                soup = BeautifulSoup(response.text, 'html.parser')
                candidates = []
                for a in soup.select('a[href]'):
                    label = ' '.join(a.stripped_strings)
                    if YEAR_RE.search(label) and PROOFSET_RE.search(label) and len(label) < 130:
                        parent = a.parent
                        candidates.append((label[:75], parent.name if parent else '', 
                                           ' '.join(parent.get('class', [])) if parent else '',
                                           parent.get_text(' ', strip=True)[:180] if parent else ''))
                    if len(candidates) >= 3:
                        break
                print('Winkelvergelijking diagnostics:',name, repr(candidates))
                for a in soup.select('a[href]'):
                    label = ' '.join(a.stripped_strings)
                    if exact_year(label) is None:
                        continue
                    chain = []
                    for depth, el in enumerate([a, *list(a.parents)[:11]]):
                        t = el.get_text(' ', strip=True)
                        chain.append((depth, el.name, ' '.join(el.get('class', []))[:55],
                                      len(t), len(el.select('a[href]')), len(MONEY_RE.findall(t)), t[-75:]))
                        if el.name in ('body', 'html'):
                            break
                    print('Winkelvergelijking DOM:',name, repr(chain))
                    break
            reachable.add(name)
            for offer in parsed:
                k = (name, offer['year'])
                if k not in offers or offers[k]['price'] > offer['price']:
                    offers[k] = offer
        except (requests.RequestException, RuntimeError, ValueError) as exc:
            errors.append(f'{name}: {str(exc)[:90]}')
        time.sleep(.4)
    if not reachable:
        stale = cached_shops(24 * 3600)
        if stale:
            stale['outdated'] = True
            stale['errors'] = errors
            return stale
    data = {
        'schema_version': CACHE_SCHEMA,
        'checked_at': datetime.now(timezone.utc).isoformat(timespec='seconds'),
        'offers': list(offers.values()),
        'checked_shops': sorted(reachable),
        'errors': errors, 'outdated': False,
    }
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf8')
    return data


def match_shops(deals: list[dict], catalog: dict) -> None:
    by_year = {}
    for offer in catalog.get('offers', []):
        try:
            year, price = int(offer['year']), float(offer['price'])
            if not (1 <= price <= 5000):
                continue
            if offer.get('availability') == 'uitverkocht':
                continue
            by_year.setdefault(year, []).append(offer)
        except (ValueError, TypeError, KeyError):
            continue
    for d in deals:
        d.update(shop_price=None, shop_name=None, shop_url=None,
                 shop_checked_at=catalog.get('checked_at'),
                 shop_availability=None, shop_difference=None, shop_matches=0)
        if d.get('bundle') or d.get('set_type') in ('special', 'gemengd') or len(d.get('years', [])) != 1:
            d['shop_note'] = 'Geen betrouwbare vergelijking voor speciale uitgave of bundel'
            continue
        year = d['years'][0]
        choices = sorted(by_year.get(year, []), key=lambda x: (x['price'], x['shop']))
        if not choices:
            d['shop_note'] = 'Geen overeenkomende webshopprijs gevonden bij gecontroleerde winkels'
            continue
        # Voorkeur voor expliciet op voorraad. Anders prijs wel tonen met status niet bevestigd.
        available = [o for o in choices if o.get('availability') == 'beschikbaar']
        chosen = min(available, key=lambda x: x['price']) if available else choices[0]
        d['shop_price'] = chosen['price']
        d['shop_name'] = chosen['shop']
        d['shop_url'] = chosen['url']
        d['shop_availability'] = chosen['availability']
        d['shop_matches'] = len(choices)
        d['shop_note'] = ('Goedkoopste gevonden bevestigde voorraadprijs bij gecontroleerde winkels; verzending niet meegerekend'
                         if available else 'Winkelprijs gevonden, actuele voorraad niet bevestigd; verzending niet meegerekend')
        if d.get('purchase_total') is not None:
            d['shop_difference'] = round(chosen['price'] - d['purchase_total'], 2)
