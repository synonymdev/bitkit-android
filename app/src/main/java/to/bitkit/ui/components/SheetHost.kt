package to.bitkit.ui.components

enum class SheetSize { LARGE, MEDIUM, SMALL, CALENDAR; }

/**@param priority Priority levels for timed sheets (higher number = higher priority)*/
enum class TimedSheetType(val priority: Int) {
    APP_UPDATE(priority = 5),
    BACKUP(priority = 4),
    NOTIFICATIONS(priority = 3),
    QUICK_PAY(priority = 2),
    HIGH_BALANCE(priority = 1)
}
