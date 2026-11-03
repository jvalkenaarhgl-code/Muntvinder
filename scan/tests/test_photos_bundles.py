import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scanner import mp_images, mp_listing, identify, parse_catawiki_catalog, parse_catawiki_lot


def test_marktplaats_photos_from_picture_objects_and_protocol_relative_fallback():
    obj = {
      'itemId': 'm2451234500',
      'title': 'Nederland proofset 2022',
      'vipUrl': '/v/test/m2451234500-set',
      'priceInfo': {'priceCents': 2500, 'priceType': 'FIXED'},
      'pictures': [
        {'url':'https://images.marktplaats.com/templated/#.jpg',
         'largeUrl':'https://images.marktplaats.com/photo/front.jpg',
         'mediumUrl':'https://images.marktplaats.com/photo/front-small.jpg'},
        {'mediumUrl':'//images.marktplaats.com/photo/back.jpg'}
      ],
      'imageUrls':['//images.marktplaats.com/photo/front.jpg', '//images.marktplaats.com/photo/extra.jpg']
    }
    listing = mp_listing(obj)
    assert listing['image_url'] == 'https://images.marktplaats.com/photo/front.jpg'
    assert listing['image_urls'] == [
      'https://images.marktplaats.com/photo/front.jpg',
      'https://images.marktplaats.com/photo/back.jpg',
      'https://images.marktplaats.com/photo/extra.jpg'
    ]


def test_ignore_untrusted_image_hosts_and_empty_photos():
    obj={'pictures':[{'largeUrl':'https://evil.example/track.jpg'},
                     {'largeUrl':'http://i.marktplaats.com/image.jpg'}],
         'imageUrls':['javascript:alert(1)', 'data:image/svg+xml;base64,eA==']}
    assert mp_images(obj) == ['https://i.marktplaats.com/image.jpg']
    assert mp_images({}) == []


def test_bundles_with_ten_sets_and_multiple_years():
    x=identify('Partij 12 proofsets Nederland 2018')
    assert x['bundle'] is True and x['count']==12
    assert identify('Nederland proofset 2018')['bundle'] is False
    assert identify('Bundel Nederlandse proofsets 2018 2019')['bundle'] is True
    assert identify('Collectie proofjaarsets Nederland 2022 en 2024')['bundle'] is True


def test_catawiki_photo_only_from_lot_open_graph_and_only_bundle():
    html='<a href="/nl/l/123123-nederland-proofset-2020-2022">Nederland proofset 2020 2022 bundel</a>'
    one=list(parse_catawiki_catalog(html,'https://www.catawiki.com/nl/x/547301'))
    assert len(one)==1 and one[0]['image_urls']==[]
    out=parse_catawiki_lot('<meta property="og:image" content="https://cdn.catawiki.net/photos/bundle.jpg"/>'
                           '<div>Huidig bod</div><div>€ 30</div>',one[0])
    assert out['image_url']=='https://cdn.catawiki.net/photos/bundle.jpg'
    assert out['image_urls']==[out['image_url']]
