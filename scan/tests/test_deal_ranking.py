import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shop_prices import rank_opportunities


def item(identifier, price, low, high, shop, kind='FIXED', comparables=2):
    return {
        'id': identifier,
        'source':'Marktplaats',
        'price_kind':kind,
        'price':price,
        'purchase_total':price,
        'profit_low':low,
        'profit_high':high,
        'shop_price':shop,
        'comparison_count':comparables,
        'discount_ratio': None if high is None else 1-price/max(high,1),
    }


def test_best_conservative_deals_before_shop_discount_and_unpriced_offers():
    listings=[
        item('cheap-but-not-valued',15,None,None,25),
        item('negative',40,-10,-2,50),
        item('excellent',40,20,30,70),
        item('small-profit',30,1,5,40),
        item('bid',40,20,30,70,'MIN_BID'),
    ]
    ranked=rank_opportunities(listings)
    assert ranked[0]['id']=='excellent'
    assert ranked[1]['id']=='bid'
    assert ranked[2]['id']=='small-profit'
    assert ranked[3]['id']=='cheap-but-not-valued'
    assert all('deal_score' in d and 'deal_label' in d for d in ranked)
    assert all(d['deal_position']==i+1 for i,d in enumerate(ranked))


def test_alerts_only_top_three_and_nonzero_conservative_profit():
    ranked=rank_opportunities([
        item('one-euro',25,1,4,30),
        item('no-matching-shop',30,10,20,None),
        item('zero-profit',30,0,5,42),
    ])
    one=next(d for d in ranked if d['id']=='one-euro')
    assert one['alert_candidate'] is True
    zero=next(d for d in ranked if d['id']=='zero-profit')
    assert zero['alert_candidate'] is False
    # Ook zonder webshop kan een advertentie met >=3 echte Marktplaatsvergelijkingen
    # als interessante nieuwe kans gemarkeerd worden.
    count=rank_opportunities([item('good-comparables',20,5,10,None,comparables=3)])
    assert count[0]['alert_candidate'] is True


def test_bidding_is_labeled_and_not_guaranteed():
    ranked=rank_opportunities([
        item('bid-2018',50,30,45,124,'MIN_BID'),
        item('fixed-low',50,-5,5,65),
    ])
    assert ranked[0]['id']=='bid-2018'
    assert ranked[0]['alert_candidate'] is True
    assert 'bod' in ranked[0]['deal_label'].lower()


def test_more_than_three_candidates_does_not_spam():
    ranked=rank_opportunities([item(str(i),50,12-i,20-i,100)
                               for i in range(10)])
    assert sum(1 for d in ranked if d['alert_candidate']) == 3
