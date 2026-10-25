import os
import math
from PIL import Image, ImageDraw, ImageFont, ImageFilter

def create_visor_logo(size=512):
    # High resolution canvas for super-sampling (2x anti-aliasing)
    scale = 2
    w = size * scale
    h = size * scale
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    center_x = w / 2
    center_y = h / 2

    # 1. Base Dark Squircle / Shield Background
    # Rounded rectangle padding
    pad = 28 * scale
    corner_r = 110 * scale
    
    # Outer Glow / Border
    glow_color = (0, 229, 255, 80)
    for g in range(8 * scale, 0, -scale):
        alpha = int(30 * (1 - g / (8 * scale)))
        draw.rounded_rectangle(
            [pad - g, pad - g, w - pad + g, h - pad + g],
            radius=corner_r + g,
            outline=(0, 229, 255, alpha),
            width=2 * scale
        )

    # Deep Space Navy Gradient
    # Draw vertical linear gradient within rounded rect mask
    grad_mask = Image.new("L", (w, h), 0)
    mask_draw = ImageDraw.Draw(grad_mask)
    mask_draw.rounded_rectangle([pad, pad, w - pad, h - pad], radius=corner_r, fill=255)

    grad_img = Image.new("RGBA", (w, h))
    grad_draw = ImageDraw.Draw(grad_img)
    top_color = (13, 20, 36, 255)      # Deep Midnight Navy
    bottom_color = (5, 8, 18, 255)     # Pitch Black Titanium
    for y in range(h):
        interp = y / h
        r = int(top_color[0] * (1 - interp) + bottom_color[0] * interp)
        g = int(top_color[1] * (1 - interp) + bottom_color[1] * interp)
        b = int(top_color[2] * (1 - interp) + bottom_color[2] * interp)
        grad_draw.line([(0, y), (w, y)], fill=(r, g, b, 255))

    img.paste(grad_img, (0, 0), grad_mask)
    draw = ImageDraw.Draw(img)

    # 2. Sleek Tech Border around the Squircle
    draw.rounded_rectangle(
        [pad, pad, w - pad, h - pad],
        radius=corner_r,
        outline=(0, 229, 255, 200),
        width=int(3.5 * scale)
    )

    # Secondary inner golden accent line
    inner_pad = pad + 10 * scale
    draw.rounded_rectangle(
        [inner_pad, inner_pad, w - inner_pad, h - inner_pad],
        radius=corner_r - 8 * scale,
        outline=(255, 179, 0, 90),
        width=int(1.5 * scale)
    )

    # 3. HUD Perception Grid & Concentric Radar Rings
    radar_center = (center_x, center_y - 12 * scale)
    for radius in [170 * scale, 125 * scale, 75 * scale]:
        draw.ellipse(
            [radar_center[0] - radius, radar_center[1] - radius,
             radar_center[0] + radius, radar_center[1] + radius],
            outline=(0, 229, 255, 45),
            width=int(1.5 * scale)
        )

    # Radar crosshair tick marks
    cross_len = 24 * scale
    for angle_deg in [0, 45, 90, 135, 180, 225, 270, 315]:
        rad = math.radians(angle_deg)
        r1 = 160 * scale
        r2 = r1 + cross_len
        p1 = (radar_center[0] + r1 * math.cos(rad), radar_center[1] + r1 * math.sin(rad))
        p2 = (radar_center[0] + r2 * math.cos(rad), radar_center[1] + r2 * math.sin(rad))
        draw.line([p1, p2], fill=(0, 229, 255, 120), width=int(2 * scale))

    # 4. Stylized Futuristic Security Shield Contour
    shield_pts = [
        (center_x, radar_center[1] - 175 * scale),            # Top peak
        (radar_center[0] + 160 * scale, radar_center[1] - 120 * scale), # Top right shoulder
        (radar_center[0] + 145 * scale, radar_center[1] + 60 * scale),  # Mid right
        (center_x, radar_center[1] + 185 * scale),            # Bottom tip
        (radar_center[0] - 145 * scale, radar_center[1] + 60 * scale),  # Mid left
        (radar_center[0] - 160 * scale, radar_center[1] - 120 * scale), # Top left shoulder
    ]
    draw.polygon(shield_pts, outline=(0, 229, 255, 230), width=int(4 * scale))

    # 5. Core Automotive Camera Aperture / Lens
    lens_r = 88 * scale
    # Outer metallic lens bevel
    draw.ellipse(
        [radar_center[0] - lens_r, radar_center[1] - lens_r,
         radar_center[0] + lens_r, radar_center[1] + lens_r],
        fill=(18, 28, 48, 240),
        outline=(255, 179, 0, 230),
        width=int(4 * scale)
    )

    # Inner optical glass
    glass_r = 68 * scale
    glass_mask = Image.new("L", (w, h), 0)
    g_mask_draw = ImageDraw.Draw(glass_mask)
    g_mask_draw.ellipse(
        [radar_center[0] - glass_r, radar_center[1] - glass_r,
         radar_center[0] + glass_r, radar_center[1] + glass_r],
        fill=255
    )
    glass_grad = Image.new("RGBA", (w, h))
    gg_draw = ImageDraw.Draw(glass_grad)
    for y in range(int(radar_center[1] - glass_r), int(radar_center[1] + glass_r + 1)):
        t = (y - (radar_center[1] - glass_r)) / (2 * glass_r)
        cr = int(0 * (1 - t) + 0 * t)
        cg = int(229 * (1 - t) + 90 * t)
        cb = int(255 * (1 - t) + 180 * t)
        alpha = int(200 * (1 - t) + 240 * t)
        gg_draw.line([(0, y), (w, y)], fill=(cr, cg, cb, alpha))
    img.paste(glass_grad, (0, 0), glass_mask)
    draw = ImageDraw.Draw(img)

    # Pupil deep center
    pupil_r = 34 * scale
    draw.ellipse(
        [radar_center[0] - pupil_r, radar_center[1] - pupil_r,
         radar_center[0] + pupil_r, radar_center[1] + pupil_r],
        fill=(5, 10, 22, 255),
        outline=(0, 229, 255, 240),
        width=int(2.5 * scale)
    )

    # Optical reflection glare (crescent)
    glare_r = 20 * scale
    draw.ellipse(
        [radar_center[0] - 18 * scale - glare_r, radar_center[1] - 18 * scale - glare_r,
         radar_center[0] - 18 * scale + glare_r, radar_center[1] - 18 * scale + glare_r],
        fill=(255, 255, 255, 160)
    )

    # 6. Glowing Red Recording Dot (REC)
    rec_r = 11 * scale
    rec_center = (radar_center[0] + 58 * scale, radar_center[1] - 58 * scale)
    # Red glow
    for gr in range(6 * scale, 0, -scale):
        draw.ellipse(
            [rec_center[0] - rec_r - gr, rec_center[1] - rec_r - gr,
             rec_center[0] + rec_r + gr, rec_center[1] + rec_r + gr],
            fill=(255, 30, 30, 28)
        )
    draw.ellipse(
        [rec_center[0] - rec_r, rec_center[1] - rec_r,
         rec_center[0] + rec_r, rec_center[1] + rec_r],
        fill=(255, 34, 34, 255),
        outline=(255, 200, 200, 240),
        width=int(1.5 * scale)
    )

    # 7. AI Bounding Box Reticles (Corner Brackets)
    bracket_size = 32 * scale
    b_margin = 115 * scale
    b_color = (0, 229, 255, 240)
    bw = int(3.5 * scale)

    # Top-Left Bracket
    tl_x = radar_center[0] - b_margin
    tl_y = radar_center[1] - b_margin
    draw.line([(tl_x, tl_y + bracket_size), (tl_x, tl_y), (tl_x + bracket_size, tl_y)], fill=b_color, width=bw)

    # Top-Right Bracket
    tr_x = radar_center[0] + b_margin
    tr_y = radar_center[1] - b_margin
    draw.line([(tr_x - bracket_size, tr_y), (tr_x, tr_y), (tr_x, tr_y + bracket_size)], fill=b_color, width=bw)

    # Bottom-Left Bracket
    bl_x = radar_center[0] - b_margin
    bl_y = radar_center[1] + b_margin
    draw.line([(bl_x, bl_y - bracket_size), (bl_x, bl_y), (bl_x + bracket_size, bl_y)], fill=b_color, width=bw)

    # Bottom-Right Bracket
    br_x = radar_center[0] + b_margin
    br_y = radar_center[1] + b_margin
    draw.line([(br_x - bracket_size, br_y), (br_x, br_y), (br_x, br_y - bracket_size)], fill=b_color, width=bw)

    # 8. Bottom Tech Brand "VISOR"
    try:
        font = ImageFont.truetype("arialbd.ttf", int(36 * scale))
    except Exception:
        font = ImageFont.load_default()

    brand_text = "VISOR"
    # Measure text
    bbox = draw.textbbox((0, 0), brand_text, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    text_pos = (center_x - tw / 2, h - pad - 42 * scale)

    # Subtle cyan drop-glow for text
    draw.text((text_pos[0], text_pos[1] + 1), brand_text, font=font, fill=(0, 229, 255, 120))
    draw.text(text_pos, brand_text, font=font, fill=(255, 255, 255, 255))

    # Downsample with Lanczos high-quality resampling for anti-aliasing
    final_img = img.resize((size, size), Image.Resampling.LANCZOS)
    return final_img

def create_round_icon(base_icon):
    size = base_icon.width
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse([4, 4, size - 4, size - 4], fill=255)
    
    round_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    round_img.paste(base_icon, (0, 0), mask)
    return round_img

def main():
    print("Generating high-resolution VISOR Logo...")
    logo_512 = create_visor_logo(512)
    
    # Save main branding logo
    docs_dir = os.path.join(os.path.dirname(__file__), "..", "docs")
    os.makedirs(docs_dir, exist_ok=True)
    logo_path = os.path.join(docs_dir, "app_logo.png")
    logo_512.save(logo_path, "PNG")
    print(f"Saved: {logo_path}")

    # Standard Android Mipmap Sizes:
    # mdpi: 48x48
    # hdpi: 72x72
    # xhdpi: 96x96
    # xxhdpi: 144x144
    # xxxhdpi: 192x192
    res_dir = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
    mipmap_configs = [
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
    ]

    for folder_name, dim in mipmap_configs:
        target_dir = os.path.join(res_dir, folder_name)
        os.makedirs(target_dir, exist_ok=True)

        icon = logo_512.resize((dim, dim), Image.Resampling.LANCZOS)
        icon_path = os.path.join(target_dir, "ic_launcher.png")
        icon.save(icon_path, "PNG")

        round_icon = create_round_icon(icon)
        round_icon_path = os.path.join(target_dir, "ic_launcher_round.png")
        round_icon.save(round_icon_path, "PNG")

        print(f"Generated {folder_name} ({dim}x{dim})")

    print("All app icons successfully generated!")

if __name__ == "__main__":
    main()
