"""Compare the actual HTML output with independent WGS84 geodesic bearings.

Reference azimuths were generated with GeographicLib's Geodesic.WGS84.Inverse.
Playwright is used only for this development check; the app has no dependency.
"""

from pathlib import Path

from playwright.sync_api import sync_playwright


KAABA = (21.4225, 39.8262)
CASES = {
    "New York, USA": (40.7128, -74.0060, 58.40),
    "London, UK": (51.5074, -0.1278, 118.87),
    "Jakarta, Indonesia": (-6.2088, 106.8456, 295.02),
    "Cape Town, South Africa": (-33.9249, 18.4241, 23.47),
    "Tokyo, Japan": (35.6762, 139.6503, 293.07),
    "Sydney, Australia": (-33.8688, 151.2093, 277.32),
    "São Paulo, Brazil": (-23.5505, -46.6333, 69.09),
    "Honolulu, USA": (21.3099, -157.8581, 337.02),
    "Anchorage, USA": (61.2181, -149.9003, 350.90),
    "Suva, Fiji": (-18.1416, 178.4419, 281.83),
}


def angular_error(actual, expected):
    return abs((actual - expected + 180) % 360 - 180)


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True)
    page = browser.new_page()
    page.goto(Path(__file__).resolve().parents[1].joinpath("index.html").as_uri())

    for place, (latitude, longitude, expected) in CASES.items():
        page.evaluate("([lat, lon]) => window.qiblaNativeLocation(lat, lon, 25)", [latitude, longitude])
        actual = float(page.locator("#bearing").inner_text().removesuffix("°"))
        error = angular_error(actual, expected)
        assert error < 0.35, f"{place}: {actual}° vs WGS84 {expected}° ({error:.2f}° difference)"
        print(f"{place}: {actual:.1f}° (WGS84 {expected:.2f}°; {error:.2f}° difference)")

    for latitude, longitude in (KAABA, (-KAABA[0], KAABA[1] - 180)):
        page.evaluate("([lat, lon]) => window.qiblaNativeLocation(lat, lon, 25)", [latitude, longitude])
        assert page.locator("#bearing").inner_text() == "—"
        assert "no unique Qibla bearing" in page.locator("#status").inner_text()

    page.evaluate("window.qiblaNativeLocation(40.7128, -74.0060, 25)")
    assert page.locator("#needle").evaluate("element => getComputedStyle(element).visibility") == "hidden"

    for heading, expected_turn in ((0, 58.5), (90, -31.5)):
        compass_page = browser.new_page()
        compass_page.add_init_script(
            "window.AndroidBridge = {startCompass(){}, getLocation(){}, setDeclination(){}, stopCompass(){}}"
        )
        compass_page.goto(Path(__file__).resolve().parents[1].joinpath("index.html").as_uri())
        compass_page.evaluate("window.qiblaNativeLocation(40.7128, -74.0060, 25)")
        compass_page.evaluate("heading => window.qiblaNativeHeading(heading)", heading)
        assert compass_page.locator("#needle").evaluate("element => getComputedStyle(element).visibility") == "visible"
        turn = compass_page.locator("#needle").evaluate(
            "element => Number(element.style.transform.match(/rotate\\(([-\\d.]+)deg\\)/)[1])"
        )
        assert abs(turn - expected_turn) < 0.1, f"Heading {heading}°: arrow turn {turn}°"
        compass_page.close()

    browser.close()

print("All bearing, arrow, and degenerate-point checks passed.")
