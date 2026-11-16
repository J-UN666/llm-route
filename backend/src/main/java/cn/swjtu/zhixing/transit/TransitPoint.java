package cn.swjtu.zhixing.transit;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 带稳定 ID 的经纬度点。经度始终在前，纬度在后。 */
public record TransitPoint(
        @NotBlank(message = "pointId 不能为空") String pointId,
        @NotNull(message = "longitude 不能为空")
        @DecimalMin(value = "-180", message = "longitude 超出范围")
        @DecimalMax(value = "180", message = "longitude 超出范围") Double longitude,
        @NotNull(message = "latitude 不能为空")
        @DecimalMin(value = "-90", message = "latitude 超出范围")
        @DecimalMax(value = "90", message = "latitude 超出范围") Double latitude,
        @NotBlank(message = "coordinateSystem 不能为空") String coordinateSystem) {
}
