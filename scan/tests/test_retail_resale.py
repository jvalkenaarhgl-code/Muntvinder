import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scanner import mp_listing, calc_market
from shop_prices import match_shops, refine_resale_with_shops


def listing(item, price, kind='FIXED'):
    return mp_listing({
        'itemId': item,
        'title': 'KNM Proofset Nederland 2020 Rotterdam',
        'priceInfo': {'priceCents': int(price * 100), 'priceType': kind},
        'vipUrl': '/v/munten/' + item + '-proofset-nederland-2020'
    })


def shop(price, availability='onbevestigd'):
    return {'checked_at': '2026-11-03T13:00:00+00:00',
            'offers': [{'year': 2020, 'price': price,
                        'shop': 'Hansmunt', 'url': 'https://hansmunt.nl/2020',
                        'availability': availability}]}


def test_one_high_marktplaats_asking_price_capped_by_cheaper_webshop():
    bid = listing('m2345678901', 50, 'MIN_BID')
    ask = listing('m2345678902', 100)
    deals = calc_market([bid, ask])
    match_shops(deals, shop(54.95))
    deals = refine_resale_with_shops(deals)
    d = next(x for x in deals if x['id'] == bid['id'])
    assert d['comparison_count'] == 1
    assert d['resale_low'] == 41.21
    assert d['resale_high'] == 49.46
    assert d['profit_low'] == -8.79
    assert d['profit_high'] == -0.54
    assert d['resale_high'] < d['shop_price']
    assert 'geen gerealiseerde verkoopprijs' in d['resale_note']
    assert 'voorraad niet bevestigd' in d['resale_note'].lower()


def test_shop_without_marktplaats_comparable_is_not_a_sale_estimate():
    bid = listing('m2345678903', 40, 'MIN_BID')
    deals = calc_market([bid])
    match_shops(deals, shop(54.95, 'beschikbaar'))
    ds = refine_resale_with_shops(deals)
    assert ds[0]['shop_price'] == 54.95
    assert ds[0]['resale_low'] is None
    assert ds[0]['resale_high'] is None
    assert ds[0]['profit_low'] is None
    assert ds[0]['rank'] is None


def test_too_few_comparables_and_no_shop_means_unknown_resale():
    bid = listing('m2345678913', 30, 'MIN_BID')
    ask = listing('m2345678914', 120)
    deals = calc_market([bid, ask])
    match_shops(deals, {})
    ds = refine_resale_with_shops(deals)
    assert all(x['resale_low'] is None and x['profit_high'] is None for x in ds)
    assert len(ds) == 2


def test_good_marktplaats_comps_still_cap_below_shop_and_maintain_sort():
    deals = calc_market([listing('m23456789'+str(i), p) for i,p in enumerate([40, 50, 60, 70])])
    match_shops(deals, shop(45, 'beschikbaar'))
    ds = refine_resale_with_shops(deals)
    assert len(ds) == 4
    assert all(d['resale_high'] <= 40.5 for d in ds)
    assert all(d['profit_high'] is not None for d in ds)
