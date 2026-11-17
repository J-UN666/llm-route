/**
 * W1-11 分段元信息：把后端 `JourneySegment.mode` 映射为展示文案、类别和地图样式。
 *
 * 这里的类别只用于“校园步行 / 城市步行 / 乘车”三组视觉区分与详情标签，
 * 不改变后端分段顺序、不合并分段、不推算路线；未知 mode 原样保留并归入“其他”。
 */

/** 地图内部统一绘制坐标系：城市段原生坐标系，校园 WGS84 段先转换后绘制。 */
export const MAP_INTERNAL_COORDINATE_SYSTEM = 'GCJ-02';

/** 详情与地图共用的分段类别。 */
export type SegmentCategory = 'campus' | 'city-walk' | 'ride' | 'other';

export interface SegmentDisplayStyle {
  category: SegmentCategory;
  /** 图例用类别名，例如“公交·地铁”。 */
  categoryLabel: string;
  /** 该段 mode 的展示名，例如“地铁”。 */
  modeLabel: string;
  color: string;
  dashed: boolean;
  lineWidth: number;
}

const STYLES: Record<SegmentCategory, Omit<SegmentDisplayStyle, 'modeLabel'>> = {
  campus: { category: 'campus', categoryLabel: '校园步行', color: '#2e7d32', dashed: false, lineWidth: 5 },
  'city-walk': { category: 'city-walk', categoryLabel: '城市步行', color: '#f57f17', dashed: true, lineWidth: 4 },
  ride: { category: 'ride', categoryLabel: '公交·地铁', color: '#1d6fbf', dashed: false, lineWidth: 5 },
  other: { category: 'other', categoryLabel: '其他交通', color: '#6d4c41', dashed: true, lineWidth: 4 }
};

/** 与后端一致的模式名；缺失时保留“未提供”，不猜测交通方式。 */
export function normalizeMode(mode: string | null | undefined): string {
  const value = (mode || '').trim();
  return value.length > 0 ? value : 'UNKNOWN';
}

export function modeCategory(mode: string | null | undefined): SegmentCategory {
  const value = normalizeMode(mode).toUpperCase();
  if (value === 'CAMPUS_WALK') return 'campus';
  if (value === 'WALK') return 'city-walk';
  if (value === 'BUS' || value === 'METRO' || value === 'SUBWAY' || value === 'RAILWAY' || value === 'TAXI') {
    return 'ride';
  }
  return 'other';
}

export function modeLabel(mode: string | null | undefined): string {
  const value = normalizeMode(mode).toUpperCase();
  switch (value) {
    case 'CAMPUS_WALK':
      return '校园步行';
    case 'WALK':
      return '城市步行';
    case 'BUS':
      return '公交';
    case 'METRO':
    case 'SUBWAY':
      return '地铁';
    case 'RAILWAY':
      return '铁路';
    case 'TAXI':
      return '出租车';
    case 'UNKNOWN':
      return '交通方式未提供';
    default:
      // 后端可能原样保留未知 mode，这里不丢失信息。
      return `其他（${normalizeMode(mode)}）`;
  }
}

export function segmentStyle(mode: string | null | undefined): SegmentDisplayStyle {
  const category = modeCategory(mode);
  return { ...STYLES[category], modeLabel: modeLabel(mode) };
}

/** 已知距离按米展示，保留后端小数（879.07 米），null 明确显示未提供。 */
export function formatKnownMeters(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '距离未提供';
  }
  const rounded = Math.round(value * 100) / 100;
  return `${rounded} 米`;
}

/** 已知耗时按秒展示，并附约数分钟便于阅读；null 明确显示未提供。 */
export function formatKnownSeconds(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '耗时未提供';
  }
  const seconds = Math.max(0, Math.round(value));
  const minutes = Math.max(1, Math.round(seconds / 60));
  return `${seconds} 秒（约 ${minutes} 分钟）`;
}
