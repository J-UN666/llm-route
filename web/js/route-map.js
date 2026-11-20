(function () {
  'use strict';

  const {MapCanvas, pathPolyline, project, gcjToWgs84} = window.MapEngine;
  const campus = window.__CAMPUS_DATA__;
  const colors = {CAMPUS_WALK: '#18774f', WALK: '#276496', METRO: '#6949ad',
    SUBWAY: '#6949ad', BUS: '#b44c24', RAILWAY: '#43559b'};
  const tileUrl = 'https://tile.openstreetmap.org';

  function coordinates(lng, lat, system) {
    if (!Number.isFinite(lng) || !Number.isFinite(lat)
        || Math.abs(lng) > 180 || Math.abs(lat) >= 85) return null;
    const kind = (system || '').replace(/-/g, '').toUpperCase();
    if (kind === 'WGS84') return [lng, lat];
    return kind === 'GCJ02' ? gcjToWgs84(lng, lat) : null;
  }

  function location(point, fallback, color) {
    const coord = point && coordinates(point.longitude, point.latitude, point.coordinateSystem);
    return coord ? {coord, label: point.name || fallback, color} : null;
  }

  function routePaths(segment) {
    const pairs = segment.geometry?.coordinates;
    const system = segment.geometry?.coordinateSystem || segment.coordinateSystem;
    const paths = [];
    let path = [];
    for (const pair of pairs || []) {
      const point = Array.isArray(pair) ? coordinates(pair[0], pair[1], system) : null;
      if (point) path.push(point);
      else {
        if (path.length > 1) paths.push(path);
        path = [];
      }
    }
    if (path.length > 1) paths.push(path);
    return paths;
  }

  class RouteMap {
    constructor(container, status) {
      this.status = status;
      this.tiles = new Map();
      this.map = new MapCanvas(container, {minZoom: 9, maxZoom: 19});
      this.map.addLayer({id: 'base', z: 0, draw: (ctx, map) => this.drawBase(ctx, map)});
      this.map.addLayer({id: 'journey', z: 1, draw: (ctx, map) => this.drawJourney(ctx, map)});
      this.setPlan(null, 'campus');
      if (window.ResizeObserver) new ResizeObserver(() => this.fit()).observe(container);
    }

    setPlan(plan, section) {
      this.cityTiles = Boolean(plan && section !== 'campus');
      const segments = [...(plan?.segments || [])].sort((a, b) => a.sequence - b.sequence);
      const visible = segments.filter(segment => section === 'all'
        || (segment.mode === 'CAMPUS_WALK') === (section === 'campus'));
      this.traces = visible.map(segment => ({mode: segment.mode, paths: routePaths(segment)}));
      const start = segments.find(segment => segment.mode === 'CAMPUS_WALK');
      const last = segments.at(-1);
      const end = plan?.destinationPoint || (last?.mode !== 'CAMPUS_WALK' ? last?.toPoint : null);
      this.markers = [
        section !== 'city' && location(start?.fromPoint, '校园起点', '#18774f'),
        location(plan?.gateExitPoint, '出校点', '#a76116'),
        section !== 'campus' && location(end, '城市终点', '#a13b47')
      ].filter(Boolean);
      const missing = this.traces.filter(trace => !trace.paths.length).length;
      this.statusText = !plan ? '犀浦校区' : missing ? `${missing} 个分段无轨迹，详见行程分段` : '';
      this.fit();
      this.updateStatus(false);
    }

    updateStatus(tileError) {
      const value = [this.statusText, tileError ? '城市底图暂不可用' : ''].filter(Boolean).join(' · ');
      if (this.status.textContent !== value) this.status.textContent = value;
    }

    fit() {
      const points = this.markers.map(marker => marker.coord);
      for (const trace of this.traces) {
        for (const path of trace.paths) {
          for (const point of path) points.push(point);
        }
      }
      this.map.fitBounds(points.length ? points : campus.boundary[0], 45, false);
    }

    zoom(delta) {
      const {center, zoom, minZoom, maxZoom} = this.map;
      this.map.setView(center.lng, center.lat, Math.max(minZoom, Math.min(maxZoom, zoom + delta)), false);
    }

    drawBase(ctx, map) {
      ctx.fillStyle = '#eaf1f5';
      ctx.fillRect(0, 0, map.w, map.h);
      const tileError = this.cityTiles && this.drawTiles(ctx, map);
      this.updateStatus(tileError);
      if (map.zoom < 13) return;
      if (pathPolyline(ctx, map, campus.boundary[0], true)) {
        ctx.fillStyle = '#f7faf6';
        ctx.fill();
        ctx.strokeStyle = '#9eb7ca';
        ctx.setLineDash([5, 5]);
        ctx.stroke();
        ctx.setLineDash([]);
      }
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.strokeStyle = '#dbe5ea';
      ctx.lineWidth = 2;
      for (const road of campus.roads) {
        if (pathPolyline(ctx, map, road[0], false)) ctx.stroke();
      }
      ctx.fillStyle = '#dae7ef';
      ctx.strokeStyle = '#adbfce';
      ctx.lineWidth = 0.8;
      for (const building of campus.buildings) {
        if (pathPolyline(ctx, map, building.r, true)) {
          ctx.fill();
          ctx.stroke();
        }
      }
    }

    drawTiles(ctx, map) {
      const zoom = Math.floor(map.zoom);
      const span = 2 ** zoom;
      const center = project(map.center.lng, map.center.lat);
      const size = map.scale() / span;
      const left = center.x * span - map.w / (2 * size);
      const top = center.y * span - map.h / (2 * size);
      let visible = 0;
      let failed = 0;
      for (let y = Math.max(0, Math.floor(top)); y < Math.min(span, top + map.h / size); y++) {
        for (let x = Math.floor(left); x < left + map.w / size; x++) {
          const tile = this.tile(zoom, (x % span + span) % span, y);
          visible++;
          if (tile.ready) ctx.drawImage(tile.image, (x - left) * size, (y - top) * size, size + 0.5, size + 0.5);
          else if (tile.failed) failed++;
        }
      }
      return visible > 0 && failed === visible;
    }

    tile(z, x, y) {
      const key = `${z}/${x}/${y}`;
      const prior = this.tiles.get(key);
      if (prior) return prior;
      if (this.tiles.size >= 144) this.tiles.delete(this.tiles.keys().next().value);
      const image = new Image();
      const tile = {image, ready: false, failed: false};
      this.tiles.set(key, tile);
      image.onload = () => {
        tile.ready = true;
        this.map.invalidate();
      };
      image.onerror = () => {
        tile.failed = true;
        this.map.invalidate();
      };
      image.src = `${tileUrl}/${key}.png`;
      return tile;
    }

    drawJourney(ctx, map) {
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      for (const trace of this.traces) {
        for (const points of trace.paths) {
          if (!pathPolyline(ctx, map, points, false)) continue;
          ctx.strokeStyle = '#fff';
          ctx.lineWidth = 8;
          ctx.stroke();
          ctx.strokeStyle = colors[trace.mode] || '#285aa1';
          ctx.lineWidth = 5;
          ctx.stroke();
        }
      }
      ctx.font = '600 13px sans-serif';
      ctx.textBaseline = 'middle';
      for (const marker of this.markers) {
        const p = map.toScreen(...marker.coord);
        if (p.x < 0 || p.x > map.w || p.y < 0 || p.y > map.h) continue;
        ctx.fillStyle = marker.color;
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(p.x, p.y, 6, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();
        const width = Math.min(ctx.measureText(marker.label).width + 10, map.w - 8);
        const x = Math.max(4, Math.min(p.x + 8, map.w - width - 4));
        const y = Math.max(4, p.y - 27);
        ctx.fillStyle = '#fffffff3';
        ctx.fillRect(x, y, width, 20);
        ctx.fillStyle = marker.color;
        ctx.fillText(marker.label, x + 5, y + 10, width - 10);
      }
    }
  }

  window.RouteMap = RouteMap;
})();
