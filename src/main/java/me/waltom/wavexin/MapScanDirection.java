package me.waltom.wavexin;

/**
 * 方形螺旋扫描方向枚举
 * 定义四个方向及其对应的坐标偏移和视角角度
 * 
 * Minecraft yaw: 0°=南(+Z), 90°=西(-X), 180°=北(-Z), 270°=东(+X)
 */
public enum MapScanDirection {
    EAST(1, 0, 270f),    // 东 (+X) - 270°
    NORTH(0, -1, 180f),  // 北 (-Z) - 180°
    WEST(-1, 0, 90f),    // 西 (-X) - 90°
    SOUTH(0, 1, 0f);     // 南 (+Z) - 0°

    public final int dx, dz;
    public final float yaw;

    MapScanDirection(int dx, int dz, float yaw) {
        this.dx = dx;
        this.dz = dz;
        this.yaw = yaw;
    }

    /**
     * 检查当前玩家朝向是否指向该方向（允许±30°误差）
     * @param playerYaw 玩家当前 yaw 角度
     * @return 是否朝向该方向
     */
    public boolean isFacingDirection(float playerYaw) {
        float diff = playerYaw - this.yaw;
        // 处理角度跨越 -180/180 的问题
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;
        return Math.abs(diff) <= 30f;
    }

    /**
     * 获取下一个方向（顺时针）
     * @return 下一个方向
     */
    public MapScanDirection getNext() {
        return values()[(this.ordinal() + 1) % 4];
    }
}
