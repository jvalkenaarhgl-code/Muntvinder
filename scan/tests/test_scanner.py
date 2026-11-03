import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from scanner import (identify,years_from,mp_listing,mp_price,parse_catawiki_catalog,
                     parse_catawiki_lot,calc_market)

def test_years_range_only_with_count():
    assert years_from('Nederland proofset 2022/2024 (3 sets)') == [2022,2023,2024]
    assert years_from('Nederland proofset 2022/2024') == [2022,2024]

def test_sources_policy():
    assert identify('Nederland proofset 2024')['bundle'] is False
    assert identify('Nederland proofset 2014, 2016 en 2024')['bundle'] is True
    assert identify('Vaticaan Proof Set 2024') is None
    assert identify('Nederland losse proof munt 2024') is None

def test_mp_purchase():
    raw={'itemId':'m2451234567','title':'Proofset Nederland 2016','description':'Compleet in box',
         'priceInfo':{'priceCents':2500,'priceType':'FIXED'},'vipUrl':'/v/postzegels-en-munten/m2451234567-proofset-nederland-2016'}
    d=mp_listing(raw)
    assert d['price']==25
    assert d['bundle'] is False
    assert d['price_note']=='Vraagprijs'
    assert d['url'].startswith('https://www.marktplaats.nl/')
    raw['priceInfo']['priceType']='MIN_BID'
    assert mp_listing(raw)['price_kind']=='MIN_BID'

def test_cw_only_bundles_and_no_start_as_price():
    html='''<html><body><a href="/nl/l/107915913-nederland-proof-set-2022-2024-3-sets">Nederland. Proof Set 2022/2024 (3 sets)</a>
      <a href="/nl/l/100000000-nederland-proof-set-2016">Nederland. Proof Set 2016</a>
      <a href="/nl/l/888777666-france-proof-sets">France. Proof Set 2013/2017 (5 sets)</a></body></html>'''
    ds=list(parse_catawiki_catalog(html,'https://www.catawiki.com/nl/x/547301'))
    assert len(ds)==1
    assert ds[0]['years']==[2022,2023,2024]
    single=ds[0]
    parse_catawiki_lot('<div>Startbod</div><div>€ 1</div><div>Geen biedingen uitgebracht</div>',single)
    assert single['price'] is None
    parse_catawiki_lot('<div>Huidig bod</div><div>€ 47</div>',single)
    assert single['price']==47

def test_market_valuation_excludes_own_listing_and_bid_and_auction_fees():
    items=[]
    for n,p in enumerate([40,50,60,70]):
        items.append(mp_listing({'itemId':f'm24512345{n}0','title':'Nederland proofset 2016',
           'priceInfo':{'priceCents':p*100,'priceType':'FIXED'},'vipUrl':f'/v/test/m24512345{n}0-test'}))
    deal=mp_listing({'itemId':'m2456789011','title':'Nederland proofset 2016',
           'priceInfo':{'priceCents':2500,'priceType':'FIXED'},'vipUrl':'/v/test/m2456789011-test'})
    items.append(deal)
    ranked=calc_market(items)
    selected=next(x for x in ranked if x['id']==deal['id'])
    assert selected['resale_low']==44 # median(40,50,60,70)*.8
    assert selected['resale_high']==52.25
    assert selected['profit_low']==19
    assert selected['profit_high']==27.25
    assert selected['comparison_count']==4

    cw={'id':'cw:123','source':'Catawiki','title':'Nederland proofset 2014/2016 (3 sets)',
        'years':[2014,2015,2016],'set_type':'euro','bundle':True,'count':3,
        'price':50,'price_kind':'BID','url':'https://www.catawiki.com/nl/l/123-thing'}
    evaluated=calc_market([cw]+items)
    assert next(x for x in evaluated if x['id']=='cw:123')['purchase_total']==69.5

def test_not_invent_profit_with_insufficient_market():
    d=mp_listing({'itemId':'m2455678901','title':'Nederland proofset 2024','priceInfo':{'priceCents':4000,'priceType':'FIXED'},'vipUrl':'/v/test/m2455678901-test'})
    x=calc_market([d])[0]
    assert x['resale_low'] is None and x['profit_low'] is None

def test_goedkoopste_en_nieuwste_beide_gezocht(monkeypatch):
    import scanner
    class Response:
        status_code=200
        def __init__(self, kind): self.kind=kind
        def json(self):
            cents = 1999 if self.kind == 'PRICE' else 4500
            year = 2016 if self.kind == 'PRICE' else 2024
            return {'listings':[{
                'itemId': f'm2456677{year}', 'title': f'Nederland proofset {year}',
                'vipUrl': f'/v/test/m2456677{year}-test',
                'priceInfo': {'priceCents':cents,'priceType':'FIXED'}
            }]}
    class Session:
        def __init__(self): self.sorts=[]
        def get(self, endpoint, params, timeout):
            self.sorts.append(params['sortBy'])
            return Response(params['sortBy'])
    monkeypatch.setattr(scanner.time,'sleep',lambda _:None)
    s=Session()
    deals=scanner.fetch_mp(s)
    assert 'PRICE' in s.sorts and 'SORT_INDEX' in s.sorts
    assert any(d['price']==19.99 for d in deals)
    assert any(d['price']==45 for d in deals)


def test_geen_valse_winst_of_roi_drempel():
    items=[]
    for n,p in enumerate([40,45,50,55,60]):
        items.append(mp_listing({'itemId':f'm2457700{n}09',
            'title':'Nederland proofset 2022',
            'priceInfo':{'priceCents':p*100,'priceType':'FIXED'},
            'vipUrl':f'/v/test/m2457700{n}09-test'}))
    items=calc_market(items)
    assert len(items)==5
    assert items[0]['price']==40
    assert any(d['profit_low'] < 0 for d in items)
