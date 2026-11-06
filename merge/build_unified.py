#!/usr/bin/env python3
"""Compose native Muntvinder scanner and offline proofset bookkeeping into one APK."""
from pathlib import Path

ROOT = Path("android/app/src/main")
scanner = ROOT / "java/nl/muntvinder/v2/MainActivity.java"
text = scanner.read_text(encoding="utf-8")

before = "        add(top,actions);add(frame,top);"
after = """        add(top,actions);
        spacer(top,10);
        Button profit = button("▦  Mijn proofsets   •   Rendement", 0xFFEAD5B0, GREEN,
                () -> startActivity(new Intent(this, ProfitActivity.class)));
        add(top,profit);
        add(frame,top);"""
assert text.count(before) == 1, "Muntvinder toolbar changed"
text = text.replace(before, after)

before = """        add(card,go);
        add(listArea,card);spacer(listArea,13);"""
after = """        add(card,go);
        spacer(card,8);
        Button transfer = button("＋  Registreren in mijn proofsets",0xFFE9F1EB,GREEN,()->registerDeal(d));
        add(card,transfer);
        add(listArea,card);spacer(listArea,13);"""
assert text.count(before) == 1, "Muntvinder deal cards changed"
text = text.replace(before, after)

before = "    private void openGallery(JSONObject d) {"
after = """    /** Copy the advertised fields into a NEW UNSAVED record. Never treat bids as paid purchases. */
    private void registerDeal(JSONObject d) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("title", d.optString("title",""));
            JSONArray years = d.optJSONArray("years");
            if (years != null && years.length() == 1) {
                int year = years.optInt(0,0);
                if (year >= 1900 && year <= 2100) payload.put("year", year);
            }
            String title = d.optString("title","").toLowerCase(Locale.ROOT);
            payload.put("type", title.contains("proofjaarset") ? "Proofjaarset" : "Proofset");
            String kind = d.optString("price_kind","");
            // Only FIXED offers can prefill their ASKING price; all prices require verification.
            if ("FIXED".equals(kind) && d.has("price") && !d.isNull("price")) {
                double value = d.optDouble("price",0);
                if (value > 0) payload.put("buy", value);
            }
            String source = d.optString("source","");
            String url = d.optString("url","");
            payload.put("note", "Gevonden via "+source+"; controleer de definitieve aankoopprijs en verzendkosten. "+url);
            Intent i = new Intent(this, ProfitActivity.class);
            i.putExtra("deal_payload", payload.toString());
            startActivity(i);
        } catch(Exception e) {
            Toast.makeText(this,"Kon advertentie niet overnemen",Toast.LENGTH_SHORT).show();
        }
    }
    private void openGallery(JSONObject d) {"""
assert text.count(before) == 1, "Cannot attach scanner-to-portfolio integration"
text = text.replace(before, after)
scanner.write_text(text,encoding="utf-8")

manifest = ROOT / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
before = "        <activity android:name=\".MainActivity\" android:exported=\"true\">"
assert text.count(before) == 1
text = text.replace(before, '        <activity android:name=".ProfitActivity" android:exported="false" />\n' + before)
manifest.write_text(text, encoding="utf-8")

gradle = Path("android/app/build.gradle")
text = gradle.read_text(encoding="utf-8")
assert "applicationId 'nl.muntvinder.v2'" in text
text = text.replace("applicationId 'nl.muntvinder.v2'","applicationId 'nl.muntvinder.combi'")
text = text.replace("versionCode 6","versionCode 1")
text = text.replace("versionName '2.5'","versionName '3.0-combi'")
gradle.write_text(text,encoding="utf-8")

style = ROOT / "res/values/styles.xml"
text = style.read_text(encoding="utf-8")
assert '<string name="app_name">Muntvinder</string>' in text
style.write_text(text.replace('<string name="app_name">Muntvinder</string>',
                              '<string name="app_name">Muntvinder Combi</string>'),encoding="utf-8")

# Proofset Rendement 1.1 assets come directly from its successful build artifact.
assets = ROOT / "assets"
assert (assets / "app.js").exists() and (assets / "index.html").exists(), "Missing proofset UI assets"
html = assets / "index.html"
text = html.read_text(encoding="utf-8")
assert '<script src="app.js"></script>' in text
text = text.replace('<script src="app.js"></script>', '<script src="app.js"></script>\n  <script src="combi.js"></script>')
html.write_text(text,encoding="utf-8")
print("Unified Muntvinder Android project prepared.")
