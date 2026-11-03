/* =========================================================================
 * map.js — 轻量离线矢量地图引擎
 * 说明：本项目演示环境无法访问在线瓦片/CDN，因此这里用 Canvas 实现一个
 *       只依赖本地 GeoJSON 的地图内核（平移/缩放/点击/悬停/图层/动画）。
 *       如需换成 Leaflet/高德，只需替换本文件的 MapCanvas 实现。
 * ========================================================================= */
(function (global) {
  'use strict';

  var TILE = 256;

  /* ---------- Web Mercator 投影 ---------- */
  function project(lng, lat) {
    var x = (lng + 180) / 360;
    var s = Math.sin((lat * Math.PI) / 180);
    var y = 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
    return { x: x, y: y };
  }
  function unproject(x, y) {
    var lng = x * 360 - 180;
    var n = Math.PI - 2 * Math.PI * y;
    var lat = (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
    return { lng: lng, lat: lat };
  }

  /* ---------- 工具 ---------- */
  function dist2(ax, ay, bx, by) {
    var dx = ax - bx, dy = ay - by;
    return dx * dx + dy * dy;
  }

  /* =======================================================================
   * MapCanvas
   * ===================================================================== */
  function MapCanvas(container, opts) {
    opts = opts || {};
    this.container = container;
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'map-canvas';
    this.ctx = this.canvas.getContext('2d');
    container.appendChild(this.canvas);

    this.center = { lng: opts.center ? opts.center[0] : 103.9817, lat: opts.center ? opts.center[1] : 30.7660 };
    this.zoom = opts.zoom || 16.6;
    this.minZoom = opts.minZoom || 13.5;
    this.maxZoom = opts.maxZoom || 19.5;

    this.layers = [];            // {id, draw(ctx, map), z}
    this.handlers = { click: [], hover: [], move: [], zoom: [] };
    this.dpr = Math.min(global.devicePixelRatio || 1, 2.5);
    this._drag = null;
    this._anim = null;
    this._hoverPt = null;
    this._needsDraw = true;
    this._raf = null;

    this._bindEvents();
    this._resize();
    var self = this;
    if (global.ResizeObserver) {
      this._ro = new ResizeObserver(function () { self._resize(); });
      this._ro.observe(container);
    } else {
      global.addEventListener('resize', function () { self._resize(); });
    }
    this._loop();
  }

  MapCanvas.prototype._resize = function () {
    var r = this.container.getBoundingClientRect();
    this.w = Math.max(1, r.width);
    this.h = Math.max(1, r.height);
    this.canvas.width = Math.round(this.w * this.dpr);
    this.canvas.height = Math.round(this.h * this.dpr);
    this.canvas.style.width = this.w + 'px';
    this.canvas.style.height = this.h + 'px';
    this.invalidate();
  };

  /* 世界坐标（0..1） */
  MapCanvas.prototype.scale = function () {
    return TILE * Math.pow(2, this.zoom);
  };
  MapCanvas.prototype.toScreen = function (lng, lat) {
    var p = project(lng, lat);
    var c = project(this.center.lng, this.center.lat);
    var s = this.scale();
    return {
      x: (p.x - c.x) * s + this.w / 2,
      y: (p.y - c.y) * s + this.h / 2
    };
  };
  MapCanvas.prototype.fromScreen = function (x, y) {
    var c = project(this.center.lng, this.center.lat);
    var s = this.scale();
    return unproject(c.x + (x - this.w / 2) / s, c.y + (y - this.h / 2) / s);
  };
  /* 每像素代表多少米（纬度 30 度附近） */
  MapCanvas.prototype.metersPerPixel = function (lat) {
    return (156543.03392 * Math.cos((lat * Math.PI) / 180)) / Math.pow(2, this.zoom);
  };

  MapCanvas.prototype.addLayer = function (layer) {
    layer.z = layer.z == null ? 0 : layer.z;
    this.layers.push(layer);
    this.layers.sort(function (a, b) { return a.z - b.z; });
    this.invalidate();
    return layer;
  };
  MapCanvas.prototype.removeLayer = function (layer) {
    var i = this.layers.indexOf(layer);
    if (i >= 0) this.layers.splice(i, 1);
    this.invalidate();
  };
  MapCanvas.prototype.on = function (evt, fn) {
    (this.handlers[evt] || (this.handlers[evt] = [])).push(fn);
    return this;
  };
  MapCanvas.prototype.emit = function (evt, payload) {
    (this.handlers[evt] || []).forEach(function (fn) { fn(payload); });
  };

  MapCanvas.prototype.setView = function (lng, lat, zoom, animate) {
    var target = { lng: lng, lat: lat, zoom: zoom == null ? this.zoom : zoom };
    if (!animate) {
      this.center = { lng: target.lng, lat: target.lat };
      this.zoom = target.zoom;
      this.invalidate();
      return;
    }
    var self = this;
    var from = { lng: this.center.lng, lat: this.center.lat, zoom: this.zoom };
    var t0 = performance.now(), dur = 620;
    if (this._anim) cancelAnimationFrame(this._anim);
    function step(t) {
      var k = Math.min(1, (t - t0) / dur);
      var e = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
      self.center = {
        lng: from.lng + (target.lng - from.lng) * e,
        lat: from.lat + (target.lat - from.lat) * e
      };
      self.zoom = from.zoom + (target.zoom - from.zoom) * e;
      self.invalidate();
      if (k < 1) self._anim = requestAnimationFrame(step);
      else self._anim = null;
    }
    this._anim = requestAnimationFrame(step);
  };

  /* 缩放使若干点适配视野 */
  MapCanvas.prototype.fitBounds = function (pts, pad, animate) {
    pad = pad == null ? 70 : pad;
    if (!pts.length) return;
    var minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
    pts.forEach(function (p) {
      var q = project(p[0], p[1]);
      minX = Math.min(minX, q.x); maxX = Math.max(maxX, q.x);
      minY = Math.min(minY, q.y); maxY = Math.max(maxY, q.y);
    });
    var dx = Math.max(maxX - minX, 1e-9), dy = Math.max(maxY - minY, 1e-9);
    var availW = Math.max(80, this.w - pad * 2), availH = Math.max(80, this.h - pad * 2);
    var zx = Math.log2(availW / (dx * TILE));
    var zy = Math.log2(availH / (dy * TILE));
    var z = Math.min(zx, zy);
    z = Math.max(this.minZoom, Math.min(this.maxZoom, z));
    var cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
    var c = unproject(cx, cy);
    this.setView(c.lng, c.lat, z, animate);
  };

  MapCanvas.prototype.invalidate = function () {
    this._needsDraw = true;
  };

  MapCanvas.prototype._loop = function () {
    var self = this;
    function frame() {
      if (self._needsDraw) {
        self._needsDraw = false;
        self._draw();
      }
      self._raf = requestAnimationFrame(frame);
    }
    this._raf = requestAnimationFrame(frame);
  };

  MapCanvas.prototype._draw = function () {
    var ctx = this.ctx;
    ctx.save();
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.clearRect(0, 0, this.w, this.h);
    var self = this;
    this.layers.forEach(function (layer) {
      if (layer.visible === false) return;
      ctx.save();
      try { layer.draw(ctx, self); } catch (e) { console.warn('layer draw error', layer.id, e); }
      ctx.restore();
    });
    ctx.restore();
  };

  /* ---------- 交互 ---------- */
  MapCanvas.prototype._bindEvents = function () {
    var self = this;
    var c = this.canvas;

    function local(e) {
      var r = c.getBoundingClientRect();
      var t = e.touches && e.touches[0] ? e.touches[0] : e;
      return { x: t.clientX - r.left, y: t.clientY - r.top };
    }

    c.addEventListener('mousedown', function (e) {
      self._drag = { sx: local(e).x, sy: local(e).y, center: { lng: self.center.lng, lat: self.center.lat }, moved: 0 };
      c.classList.add('dragging');
    });
    global.addEventListener('mousemove', function (e) {
      if (self._drag) {
        var p = local(e);
        var dx = p.x - self._drag.sx, dy = p.y - self._drag.sy;
        self._drag.moved = Math.max(self._drag.moved, Math.abs(dx) + Math.abs(dy));
        var s = self.scale();
        var c0 = project(self._drag.center.lng, self._drag.center.lat);
        var np = unproject(c0.x - dx / s, c0.y - dy / s);
        self.center = np;
        self.invalidate();
        self.emit('move', { lng: np.lng, lat: np.lat });
      }
    });
    global.addEventListener('mouseup', function (e) {
      if (self._drag) {
        var moved = self._drag.moved;
        self._drag = null;
        c.classList.remove('dragging');
        if (moved < 4) {
          var p = local(e);
          self._click(p.x, p.y);
        }
      }
    });
    c.addEventListener('mousemove', function (e) {
      if (self._drag) return;
      var p = local(e);
      self._hoverPt = p;
      self._hover();
      self.emit('move', self.fromScreen(p.x, p.y));
    });
    c.addEventListener('mouseleave', function () {
      self._hoverPt = null;
      self._hover();
    });
    c.addEventListener('wheel', function (e) {
      e.preventDefault();
      var p = local(e);
      var before = self.fromScreen(p.x, p.y);
      var dz = -e.deltaY * 0.0022;
      self.zoom = Math.max(self.minZoom, Math.min(self.maxZoom, self.zoom + dz));
      var after = self.fromScreen(p.x, p.y);
      self.center = {
        lng: self.center.lng + (before.lng - after.lng),
        lat: self.center.lat + (before.lat - after.lat)
      };
      self.invalidate();
      self.emit('zoom', { zoom: self.zoom });
    }, { passive: false });

    /* 触摸 */
    var pinch = null;
    c.addEventListener('touchstart', function (e) {
      if (e.touches.length === 2) {
        var a = e.touches[0], b = e.touches[1];
        pinch = { d: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), zoom: self.zoom };
      } else if (e.touches.length === 1) {
        self._drag = { sx: local(e).x, sy: local(e).y, center: { lng: self.center.lng, lat: self.center.lat }, moved: 0 };
      }
    }, { passive: true });
    c.addEventListener('touchmove', function (e) {
      if (e.touches.length === 2 && pinch) {
        e.preventDefault();
        var a = e.touches[0], b = e.touches[1];
        var d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
        self.zoom = Math.max(self.minZoom, Math.min(self.maxZoom, pinch.zoom + Math.log2(d / pinch.d)));
        self.invalidate();
      } else if (e.touches.length === 1 && self._drag) {
        e.preventDefault();
        var p = local(e);
        var dx = p.x - self._drag.sx, dy = p.y - self._drag.sy;
        self._drag.moved = Math.max(self._drag.moved, Math.abs(dx) + Math.abs(dy));
        var s = self.scale();
        var c0 = project(self._drag.center.lng, self._drag.center.lat);
        self.center = unproject(c0.x - dx / s, c0.y - dy / s);
        self.invalidate();
      }
    }, { passive: false });
    c.addEventListener('touchend', function (e) {
      if (pinch) { pinch = null; return; }
      if (self._drag && self._drag.moved < 6 && e.changedTouches.length === 1) {
        var t = e.changedTouches[0];
        var r = c.getBoundingClientRect();
        self._click(t.clientX - r.left, t.clientY - r.top);
      }
      self._drag = null;
    });
  };

  MapCanvas.prototype._hitTest = function (x, y) {
    /* 从上层往下层找，图层可提供 hitTest(ctx, map, x, y) */
    for (var i = this.layers.length - 1; i >= 0; i--) {
      var L = this.layers[i];
      if (L.visible === false || !L.hitTest) continue;
      var r = L.hitTest(this.ctx, this, x, y);
      if (r) return { layer: L, hit: r };
    }
    return null;
  };

  MapCanvas.prototype._click = function (x, y) {
    var r = this._hitTest(x, y);
    var ll = this.fromScreen(x, y);
    if (r) this.emit('click', { hit: r.hit, layer: r.layer.id, lng: ll.lng, lat: ll.lat, x: x, y: y });
    else this.emit('click', { hit: null, lng: ll.lng, lat: ll.lat, x: x, y: y });
  };

  MapCanvas.prototype._hover = function () {
    var p = this._hoverPt;
    var hit = p ? this._hitTest(p.x, p.y) : null;
    this.canvas.style.cursor = hit ? 'pointer' : (this._drag ? 'grabbing' : 'grab');
    this.emit('hover', { hit: hit ? hit.hit : null, layer: hit ? hit.layer.id : null, x: p ? p.x : 0, y: p ? p.y : 0 });
  };

  /* =======================================================================
   * 通用绘制辅助
   * ===================================================================== */
  function pathPolyline(ctx, map, coords, closed) {
    if (!coords || !coords.length) return false;
    ctx.beginPath();
    for (var i = 0; i < coords.length; i++) {
      var c = coords[i];
      var p = map.toScreen(c[0], c[1]);
      if (i === 0) ctx.moveTo(p.x, p.y);
      else ctx.lineTo(p.x, p.y);
    }
    if (closed) ctx.closePath();
    return true;
  }

  /* 屏幕空间圆角矩形（用于标记） */
  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  global.MapEngine = {
    MapCanvas: MapCanvas,
    project: project,
    unproject: unproject,
    pathPolyline: pathPolyline,
    roundRect: roundRect,
    dist2: dist2
  };
})(window);
