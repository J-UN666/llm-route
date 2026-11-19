const CATEGORY_LABELS: Record<string, string> = {
  office_or_library: '办公/图书馆',
  library: '图书馆',
  campus_gate: '校门',
  public_facility: '公共设施',
  teaching_building: '教学楼',
  education: '教育设施',
  dormitory: '宿舍',
  dormitory_group: '宿舍区',
  canteen: '食堂',
  sports_facility: '体育设施',
  parking: '停车设施',
  green_space: '绿地'
};

export function categoryLabel(category: string | null): string {
  if (!category) return '类别未提供';
  return CATEGORY_LABELS[category] || category;
}
