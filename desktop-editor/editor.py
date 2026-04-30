#!/usr/bin/env python3
"""Burn-out mask editor — laptop-side per-pixel editor for the burn-out app.

Connects to the on-device Calibrate's MaskServer via TCP. Use:

    adb forward tcp:8765 localabstract:burnout-mask
    python3 editor.py

Zone list on the left shows all detected zones with their channel mask + bbox.
Selecting a zone loads its crop into the editor pane on the right. L-click /
L-drag paints at the selected severity into the zone's active channel(s);
R-click / R-drag clears. Save commits the mask + zones JSON to /data/burnout/
on the phone.
"""

from __future__ import annotations

import json
import socket
import sys
import threading

from PyQt5.QtCore import Qt, QRectF, QPoint, QRect, QSize, pyqtSignal
from PyQt5.QtGui import (
    QImage, QPixmap, QPainter, QColor, QBrush, QWheelEvent, QMouseEvent,
)
from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QHBoxLayout, QVBoxLayout, QLabel, QPushButton,
    QSpinBox, QGraphicsView, QGraphicsScene, QRubberBand, QListWidget, QListWidgetItem,
    QSplitter, QButtonGroup,
)

import shapes


HOST = "127.0.0.1"
PORT = 8765


# Display LUT: top-of-bucket mask values (63/127/191/255) are dim against any
# background. Lift them toward white while preserving severity ordering:
# 0→0 (clear), 63→159, 127→191, 191→223, 255→255. Mask data on phone is
# untouched; this is purely a render-side remap so the user can see marks.
_DISPLAY_LUT = bytes([0] + [min(255, 128 + i // 2) for i in range(1, 256)])


# Triangle orientation labels in CW order from "point up" (matches shapes.rasterize_triangle).
_ORIENT_LABELS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]


def _brighten_pixmap(pm: QPixmap, white_bg: bool = False) -> QPixmap:
    """Render the bbox crop with brightened marks on a chosen background.

    Unmarked pixels (RGB == 0,0,0) become the bbox background color: black if
    white_bg is False, white if True. Marked pixels go through the LUT.
    """
    img = pm.toImage().convertToFormat(QImage.Format_ARGB32)
    w, h = img.width(), img.height()
    n = w * h * 4
    ptr = img.bits()
    ptr.setsize(n)
    mv = memoryview(ptr)
    bg = 255 if white_bg else 0
    # Format_ARGB32 on little-endian is BGRA byte order; alpha (i+3) is left untouched.
    for i in range(0, n, 4):
        b = mv[i]; g = mv[i + 1]; r = mv[i + 2]
        if b == 0 and g == 0 and r == 0:
            mv[i] = bg; mv[i + 1] = bg; mv[i + 2] = bg
        else:
            mv[i]     = _DISPLAY_LUT[b]
            mv[i + 1] = _DISPLAY_LUT[g]
            mv[i + 2] = _DISPLAY_LUT[r]
    return QPixmap.fromImage(img)


class MaskClient:
    """Thin TCP client. Each call opens a fresh socket; serialized via _lock."""

    def __init__(self, host: str = HOST, port: int = PORT):
        self.host, self.port = host, port
        self._lock = threading.Lock()

    def _call(self, op: str, expect_blob: bool = False, **kw):
        with self._lock:
            s = socket.socket()
            s.settimeout(5)
            s.connect((self.host, self.port))
            try:
                s.sendall((json.dumps({"op": op, **kw}) + "\n").encode())
                f = s.makefile("rb")
                line = f.readline().decode()
                resp = json.loads(line)
                blob = b""
                if expect_blob and resp.get("ok") and "png_len" in resp:
                    n = resp["png_len"]
                    while len(blob) < n:
                        chunk = f.read(min(65536, n - len(blob)))
                        if not chunk:
                            break
                        blob += chunk
                return resp, blob
            finally:
                s.close()

    def ping(self):                 return self._call("ping")[0]
    def get_meta(self):             return self._call("get_meta")[0]
    def get_mask(self):             return self._call("get_mask", expect_blob=True)
    def toggle_pixels(self, edits): return self._call("toggle_pixels", edits=edits)[0]
    def save(self):                 return self._call("save")[0]


def channel_label(channels) -> str:
    """Format zone.channels (list of 0/1 per RGB) as 'R'/'G'/'B'/'RG'/.../'RGB'/'—'."""
    s = ""
    if channels[0]: s += "R"
    if channels[1]: s += "G"
    if channels[2]: s += "B"
    return s or "—"


class ZoneView(QGraphicsView):
    """Zoomable view of one zone's bbox crop.

    L/R click (no drag) emits pixel_event(x, y, button); L/R drag emits
    area_event(x0, y0, x1, y1, button) on release. button=1 left, 2 right.
    Mask coords are absolute (translated from the bbox crop).
    """

    pixel_event = pyqtSignal(int, int, int)              # x, y, button
    area_event  = pyqtSignal(int, int, int, int, int)    # x0, y0, x1, y1, button

    DRAG_THRESHOLD = 3   # view-space pixels before a press becomes a drag

    def __init__(self, parent=None):
        super().__init__(parent)
        self._scene = QGraphicsScene(self); self.setScene(self._scene)
        self._zone = None
        self._fit_done = False
        self.setRenderHint(QPainter.SmoothPixmapTransform, False)
        # Surrounding scene background is a boring neutral; user can toggle the
        # bbox interior between black and white via set_bbox_bg_dark.
        self.setBackgroundBrush(QBrush(QColor(40, 40, 40)))
        self._bbox_bg_dark = True
        self.setMouseTracking(True)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        self.setTransformationAnchor(QGraphicsView.AnchorUnderMouse)
        self._rubber = QRubberBand(QRubberBand.Rectangle, self.viewport())
        self._press_pos: QPoint | None = None
        self._press_button: int | None = None
        self._drag_active = False

    def show_zone(self, full_pixmap: QPixmap, zone: dict):
        same_bbox = (
            self._zone is not None
            and self._zone["x0"] == zone["x0"] and self._zone["y0"] == zone["y0"]
            and self._zone["x1"] == zone["x1"] and self._zone["y1"] == zone["y1"]
        )
        self._zone = zone
        x0, y0, x1, y1 = zone["x0"], zone["y0"], zone["x1"], zone["y1"]
        w = x1 - x0 + 1; h = y1 - y0 + 1
        cropped = _brighten_pixmap(full_pixmap.copy(x0, y0, w, h), white_bg=not self._bbox_bg_dark)
        self._scene.clear()
        self._scene.addPixmap(cropped)
        self.setSceneRect(QRectF(0, 0, w, h))
        if not same_bbox or not self._fit_done:
            self.fitInView(self._scene.sceneRect(), Qt.KeepAspectRatio)
            self._fit_done = True

    def fit(self):
        self.fitInView(self._scene.sceneRect(), Qt.KeepAspectRatio)

    def set_bbox_bg_dark(self, dark: bool):
        self._bbox_bg_dark = dark

    def wheelEvent(self, ev: QWheelEvent):
        delta = ev.angleDelta().y() / 120
        factor = 1.25 ** delta
        self.scale(factor, factor)

    def _view_pt_to_mask(self, view_pos: QPoint):
        if self._zone is None:
            return None
        sp = self.mapToScene(view_pos)
        sx = int(sp.x()); sy = int(sp.y())
        zw = self._zone["x1"] - self._zone["x0"]
        zh = self._zone["y1"] - self._zone["y0"]
        if 0 <= sx <= zw and 0 <= sy <= zh:
            return (self._zone["x0"] + sx, self._zone["y0"] + sy)
        return None

    def mousePressEvent(self, ev: QMouseEvent):
        if ev.button() in (Qt.LeftButton, Qt.RightButton) and self._zone is not None:
            self._press_pos = ev.pos()
            self._press_button = ev.button()
            self._drag_active = False
            self._rubber.setGeometry(QRect(self._press_pos, QSize(0, 0)))
            self._rubber.show()
        else:
            super().mousePressEvent(ev)

    def mouseMoveEvent(self, ev: QMouseEvent):
        if self._press_pos is not None:
            rect = QRect(self._press_pos, ev.pos()).normalized()
            if rect.width() >= self.DRAG_THRESHOLD or rect.height() >= self.DRAG_THRESHOLD:
                self._drag_active = True
            self._rubber.setGeometry(rect)
        else:
            super().mouseMoveEvent(ev)

    def mouseReleaseEvent(self, ev: QMouseEvent):
        if self._press_pos is not None and ev.button() == self._press_button:
            self._rubber.hide()
            release_pos = ev.pos()
            press_pos = self._press_pos
            drag = self._drag_active
            button_id = 1 if self._press_button == Qt.LeftButton else 2
            self._press_pos = None
            self._press_button = None
            self._drag_active = False
            if not drag:
                mp = self._view_pt_to_mask(release_pos)
                if mp is not None:
                    self.pixel_event.emit(mp[0], mp[1], button_id)
            else:
                tl_scene = self.mapToScene(QRect(press_pos, release_pos).normalized().topLeft())
                br_scene = self.mapToScene(QRect(press_pos, release_pos).normalized().bottomRight())
                zw = self._zone["x1"] - self._zone["x0"]
                zh = self._zone["y1"] - self._zone["y0"]
                sx0 = max(0, min(zw, int(tl_scene.x())))
                sy0 = max(0, min(zh, int(tl_scene.y())))
                sx1 = max(0, min(zw, int(br_scene.x())))
                sy1 = max(0, min(zh, int(br_scene.y())))
                if sx1 > sx0 and sy1 > sy0:
                    self.area_event.emit(
                        self._zone["x0"] + sx0, self._zone["y0"] + sy0,
                        self._zone["x0"] + sx1, self._zone["y0"] + sy1,
                        button_id,
                    )
        else:
            super().mouseReleaseEvent(ev)


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.client = MaskClient()
        self.setWindowTitle("Burn-out Mask Editor")
        self.resize(1280, 820)

        self._meta = None
        self._mask_image: QImage | None = None
        self._mask_pixmap: QPixmap | None = None
        self._selected_zone_idx = -1
        self._current_shape = 0  # 0=rect, 1=circle, 2=triangle
        self._tri_orient = 0     # 0..7, CW from "point up"

        self._build_ui()
        self.refresh()

    def _build_ui(self):
        central = QWidget(); self.setCentralWidget(central)
        outer = QVBoxLayout(central)

        # Toolbar.
        bar = QHBoxLayout()
        self.btn_refresh = QPushButton("Refresh"); self.btn_refresh.clicked.connect(self.refresh)
        self.btn_save = QPushButton("Save"); self.btn_save.clicked.connect(self.save)
        bar.addWidget(self.btn_refresh); bar.addWidget(self.btn_save)
        bar.addSpacing(12)
        bar.addWidget(QLabel("severity:"))
        self.spin_severity = QSpinBox(); self.spin_severity.setRange(1, 4); self.spin_severity.setValue(1)
        self.spin_severity.setFixedWidth(60)
        bar.addWidget(self.spin_severity)
        bar.addSpacing(12)
        bar.addWidget(QLabel("shape:"))
        self.shape_group = QButtonGroup(self)
        self.shape_group.setExclusive(True)
        self.btn_rect = QPushButton("Rect"); self.btn_rect.setCheckable(True); self.btn_rect.setChecked(True)
        self.btn_circle = QPushButton("Circle"); self.btn_circle.setCheckable(True)
        self.btn_tri = QPushButton("Triangle"); self.btn_tri.setCheckable(True)
        for i, b in enumerate((self.btn_rect, self.btn_circle, self.btn_tri)):
            self.shape_group.addButton(b, i)
            b.setFixedWidth(70)
            bar.addWidget(b)
        self.shape_group.idClicked.connect(self._on_shape_changed)
        # Triangle rotation controls — disabled until Triangle is selected.
        self.btn_rot_ccw = QPushButton("◀"); self.btn_rot_ccw.setFixedWidth(28); self.btn_rot_ccw.setEnabled(False)
        self.btn_rot_ccw.clicked.connect(lambda: self._rotate_triangle(-1))
        self.lbl_orient = QLabel("(N)"); self.lbl_orient.setFixedWidth(40); self.lbl_orient.setEnabled(False)
        self.btn_rot_cw = QPushButton("▶"); self.btn_rot_cw.setFixedWidth(28); self.btn_rot_cw.setEnabled(False)
        self.btn_rot_cw.clicked.connect(lambda: self._rotate_triangle(1))
        bar.addWidget(self.btn_rot_ccw); bar.addWidget(self.lbl_orient); bar.addWidget(self.btn_rot_cw)
        bar.addSpacing(12)
        bar.addWidget(QLabel("(L-drag = shape fill at severity • R-drag = clear • L/R-click = single px • wheel = zoom)"))
        bar.addStretch(1)
        outer.addLayout(bar)

        # Splitter: zones list (left) + zone editor (right).
        splitter = QSplitter(Qt.Horizontal)
        outer.addWidget(splitter, 1)

        # Left: zone list.
        self.zone_list = QListWidget()
        self.zone_list.itemSelectionChanged.connect(self.on_zone_selected)
        self.zone_list.setMinimumWidth(220)
        self.zone_list.setMaximumWidth(420)
        splitter.addWidget(self.zone_list)

        # Right: zone label + Fit button + ZoneView.
        right = QWidget()
        rv = QVBoxLayout(right); rv.setContentsMargins(2, 2, 2, 2)
        rh = QHBoxLayout()
        self.zone_label = QLabel("<b>(no zone selected)</b>")
        rh.addWidget(self.zone_label); rh.addStretch(1)
        self.btn_bg = QPushButton("BG: black"); self.btn_bg.setFixedWidth(90)
        self.btn_bg.clicked.connect(self._toggle_bg)
        rh.addWidget(self.btn_bg)
        btn_fit = QPushButton("Fit"); btn_fit.setFixedWidth(50)
        btn_fit.clicked.connect(lambda: self.zone_view.fit())
        rh.addWidget(btn_fit)
        rv.addLayout(rh)
        self.zone_view = ZoneView()
        self.zone_view.pixel_event.connect(self.on_pixel)
        self.zone_view.area_event.connect(self.on_area)
        rv.addWidget(self.zone_view, 1)
        splitter.addWidget(right)

        splitter.setStretchFactor(0, 0)
        splitter.setStretchFactor(1, 1)

        self.status = self.statusBar()
        self.status.showMessage("Connecting...")

    def _toggle_bg(self):
        new_dark = not self.zone_view._bbox_bg_dark
        self.zone_view.set_bbox_bg_dark(new_dark)
        self.btn_bg.setText("BG: black" if new_dark else "BG: white")
        # Re-render current zone so the new bbox bg color shows.
        z = self._selected_zone()
        if z is not None and self._mask_pixmap is not None:
            self.zone_view.show_zone(self._mask_pixmap, z)

    def _on_shape_changed(self, shape_id: int):
        self._current_shape = shape_id  # 0=rect, 1=circle, 2=triangle
        is_tri = shape_id == 2
        self.btn_rot_ccw.setEnabled(is_tri)
        self.btn_rot_cw.setEnabled(is_tri)
        self.lbl_orient.setEnabled(is_tri)

    def _rotate_triangle(self, delta: int):
        self._tri_orient = (self._tri_orient + delta) % 8
        self.lbl_orient.setText(f"({_ORIENT_LABELS[self._tri_orient]})")

    # --- network ---

    def refresh(self):
        try:
            meta = self.client.get_meta()
            r, png_bytes = self.client.get_mask()
            if not r.get("ok"):
                self.status.showMessage(f"Mask fetch failed: {r.get('error')}")
                return
            img = QImage.fromData(png_bytes, "PNG")
            pm = QPixmap.fromImage(img)
            self._meta = meta
            self._mask_image = img
            self._mask_pixmap = pm

            zones = meta.get("zones", [])
            prev_idx = self._selected_zone_idx

            self.zone_list.blockSignals(True)
            self.zone_list.clear()
            for i, z in enumerate(zones):
                ch = channel_label(z["channels"])
                w = z["x1"] - z["x0"] + 1; h = z["y1"] - z["y0"] + 1
                self.zone_list.addItem(QListWidgetItem(
                    f"Zone {i+1}: {ch}  bbox=({z['x0']},{z['y0']})-({z['x1']},{z['y1']})  size={w}×{h}"
                ))
            self.zone_list.blockSignals(False)

            if zones:
                idx = prev_idx if 0 <= prev_idx < len(zones) else 0
                self.zone_list.setCurrentRow(idx)  # triggers itemSelectionChanged → on_zone_selected
            else:
                self._selected_zone_idx = -1
                self.zone_view._scene.clear()
                self.zone_label.setText("<b>(no zones — paint via Mark mode in the Burn-out app first)</b>")

            self.status.showMessage(
                f"OK • mask {meta['mask_w']}×{meta['mask_h']} • {len(zones)} zone(s)"
            )
        except (ConnectionRefusedError, OSError) as e:
            self.status.showMessage(
                f"No connection — is `adb forward tcp:{PORT} localabstract:burnout-mask` set + Calibrate active? ({e})"
            )
        except Exception as e:
            self.status.showMessage(f"Refresh failed: {e}")

    def on_zone_selected(self):
        idx = self.zone_list.currentRow()
        if idx < 0 or self._meta is None or self._mask_pixmap is None:
            return
        zones = self._meta.get("zones", [])
        if idx >= len(zones):
            return
        self._selected_zone_idx = idx
        z = zones[idx]
        self.zone_view.show_zone(self._mask_pixmap, z)
        ch = channel_label(z["channels"])
        w = z["x1"] - z["x0"] + 1; h = z["y1"] - z["y0"] + 1
        self.zone_label.setText(
            f"<b>Zone {idx+1}: {ch}</b>  bbox=({z['x0']},{z['y0']})-({z['x1']},{z['y1']})  size={w}×{h}"
        )

    def _selected_zone(self):
        if self._meta is None or self._selected_zone_idx < 0:
            return None
        zones = self._meta.get("zones", [])
        if self._selected_zone_idx >= len(zones):
            return None
        return zones[self._selected_zone_idx]

    def _active_channels(self, zone) -> list[int]:
        return [i for i, c in enumerate(zone["channels"]) if c]

    def on_pixel(self, x: int, y: int, button: int):
        zone = self._selected_zone()
        if zone is None:
            return
        sev = 0 if button == 2 else self.spin_severity.value()
        active = self._active_channels(zone)
        if not active:
            self.status.showMessage("Selected zone has no active channels — nothing to push")
            return
        edits = [{"x": x, "y": y, "channel": ch, "severity": sev} for ch in active]
        try:
            self.client.toggle_pixels(edits)
        except Exception as e:
            self.status.showMessage(f"Push failed: {e}")
            return
        self.refresh()

    def on_area(self, x0: int, y0: int, x1: int, y1: int, button: int):
        zone = self._selected_zone()
        if zone is None:
            return
        sev = 0 if button == 2 else self.spin_severity.value()
        active = self._active_channels(zone)
        if not active:
            self.status.showMessage("Selected zone has no active channels — nothing to push")
            return
        if self._current_shape == 0:
            pixels = shapes.rasterize_rect(x0, y0, x1, y1)
            shape_name = "Rect"
        elif self._current_shape == 1:
            pixels = shapes.rasterize_circle(x0, y0, x1, y1)
            shape_name = "Circle"
        else:
            pixels = shapes.rasterize_triangle(x0, y0, x1, y1, self._tri_orient)
            shape_name = f"Triangle ({_ORIENT_LABELS[self._tri_orient]})"
        if not pixels:
            return
        edits = [
            {"x": px, "y": py, "channel": ch, "severity": sev}
            for ch in active
            for (px, py) in pixels
        ]
        try:
            self.client.toggle_pixels(edits)
        except Exception as e:
            self.status.showMessage(f"{shape_name} push failed ({len(pixels)}px): {e}")
            return
        action = "cleared" if sev == 0 else f"marked sev={sev}"
        self.status.showMessage(
            f"{shape_name} {x0},{y0}-{x1},{y1} ({len(pixels)}px) → {action}"
        )
        self.refresh()

    def save(self):
        try:
            r = self.client.save()
            if r.get("ok"):
                self.status.showMessage("Saved on phone")
            else:
                self.status.showMessage(f"Save failed: {r.get('error')}")
        except Exception as e:
            self.status.showMessage(f"Save error: {e}")


def main():
    app = QApplication(sys.argv)
    win = MainWindow()
    win.show()
    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
