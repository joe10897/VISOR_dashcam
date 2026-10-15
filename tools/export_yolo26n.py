"""
VISOR AI Dashcam - YOLO26n Export Script
Exports Ultralytics YOLO26n to an optimized, NMS-free ONNX model for Android ONNX Runtime / LiteRT.

Usage:
    pip install ultralytics onnx onnxsim
    python export_yolo26n.py --imgsz 320 --output ../app/src/main/assets/yolo26n.onnx
"""

import argparse
import os
import shutil
from ultralytics import YOLO

def export_model(model_name="yolo11n.pt", imgsz=320, output_path=None):
    print(f"[*] Loading model: {model_name}...")
    try:
        model = YOLO(model_name)
    except Exception as e:
        print(f"[!] Warning: Could not load {model_name}: {e}")
        print(f"[*] Falling back to official lightweight nano model 'yolo11n.pt'...")
        model_name = "yolo11n.pt"
        model = YOLO(model_name)

    print(f"[*] Exporting to ONNX format (imgsz={imgsz}, NMS-free end-to-end)...")
    # Export with ONNX format, dynamic=False for best NPU optimization on mobile
    exported_path = model.export(
        format="onnx",
        imgsz=imgsz,
        dynamic=False,
        simplify=True,
        opset=17
    )

    print(f"[+] Model exported to: {exported_path}")

    if output_path:
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        shutil.copyfile(exported_path, output_path)
        print(f"[+] Successfully copied model to Android assets: {output_path}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Export YOLO Nano model for VISOR Dashcam")
    parser.add_argument("--model", type=str, default="yolo11n.pt", help="Model name or weights path")
    parser.add_argument("--imgsz", type=int, default=320, help="Input image dimension (e.g., 320 for speed, 640 for accuracy)")
    parser.add_argument("--output", type=str, default="../app/src/main/assets/yolo26n.onnx", help="Destination path in Android app")
    args = parser.parse_args()

    export_model(model_name=args.model, imgsz=args.imgsz, output_path=args.output)
