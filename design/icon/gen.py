from playwright.sync_api import sync_playwright
import pathlib, subprocess, os
import os
W = os.path.dirname(os.path.abspath(__file__))
with sync_playwright() as p:
    b=p.chromium.launch(executable_path="/usr/local/bin/chromium",
        args=["--no-sandbox","--disable-dev-shm-usage","--force-device-scale-factor=1"])
    pg=b.new_page(viewport={"width":1200,"height":1200}, device_scale_factor=1)
    pg.goto(pathlib.Path(f"{W}/layers.html").as_uri(), wait_until="networkidle")
    pg.wait_for_timeout(700)
    pg.query_selector("#bg").screenshot(path=f"{W}/bg_432.png")
    pg.query_selector("#fg").screenshot(path=f"{W}/fg_432.png", omit_background=True)
    pg.query_selector("#fgprev").screenshot(path=f"{W}/fg_preview.png")
    pg.query_selector("#full").screenshot(path=f"{W}/full_432.png")
    pg.query_selector("#store").screenshot(path=f"{W}/store_512.png")
    b.close()
print("渲染完成")
