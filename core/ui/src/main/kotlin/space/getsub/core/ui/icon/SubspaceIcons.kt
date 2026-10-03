// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.icon

import space.getsub.core.ui.R

/**
 * Material Symbols Rounded, imported as vector drawables by
 * `scripts/import-material-symbols.sh` (M8.6 spec §2.7). Use with
 * `Icon(painterResource(SubspaceIcons.Shield), contentDescription = …)`.
 * `…Filled` is the filled variant. To add one, add it to the script and
 * re-run it, then add the constant here; SubspaceIconsTest checks both lists.
 */
object SubspaceIcons {
    val Add: Int = R.drawable.sym_add
    val AltRoute: Int = R.drawable.sym_alt_route
    val Apps: Int = R.drawable.sym_apps
    val ArrowBack: Int = R.drawable.sym_arrow_back
    val ArrowOutward: Int = R.drawable.sym_arrow_outward
    val BatterySaver: Int = R.drawable.sym_battery_saver
    val Block: Int = R.drawable.sym_block
    val Bolt: Int = R.drawable.sym_bolt
    val Category: Int = R.drawable.sym_category
    val Check: Int = R.drawable.sym_check
    val CheckFilled: Int = R.drawable.sym_check_filled
    val ChevronRight: Int = R.drawable.sym_chevron_right
    val Close: Int = R.drawable.sym_close
    val CloudDownload: Int = R.drawable.sym_cloud_download
    val ContentPaste: Int = R.drawable.sym_content_paste
    val Contrast: Int = R.drawable.sym_contrast
    val DarkMode: Int = R.drawable.sym_dark_mode
    val Delete: Int = R.drawable.sym_delete
    val Dns: Int = R.drawable.sym_dns
    val DnsFilled: Int = R.drawable.sym_dns_filled
    val Download: Int = R.drawable.sym_download
    val Eco: Int = R.drawable.sym_eco
    val Edit: Int = R.drawable.sym_edit
    val ExpandMore: Int = R.drawable.sym_expand_more
    val Fingerprint: Int = R.drawable.sym_fingerprint
    val FolderOpen: Int = R.drawable.sym_folder_open
    val History: Int = R.drawable.sym_history
    val Home: Int = R.drawable.sym_home
    val HomeFilled: Int = R.drawable.sym_home_filled
    val Info: Int = R.drawable.sym_info
    val KeyboardArrowDown: Int = R.drawable.sym_keyboard_arrow_down
    val KeyboardArrowUp: Int = R.drawable.sym_keyboard_arrow_up
    val LightMode: Int = R.drawable.sym_light_mode
    val ListAlt: Int = R.drawable.sym_list_alt
    val Lock: Int = R.drawable.sym_lock
    val MoreVert: Int = R.drawable.sym_more_vert
    val NetworkPing: Int = R.drawable.sym_network_ping
    val NorthEast: Int = R.drawable.sym_north_east
    val OpenInNew: Int = R.drawable.sym_open_in_new
    val Palette: Int = R.drawable.sym_palette
    val PlaylistAddCheck: Int = R.drawable.sym_playlist_add_check
    val PowerSettingsNew: Int = R.drawable.sym_power_settings_new
    val ProgressActivity: Int = R.drawable.sym_progress_activity
    val Public: Int = R.drawable.sym_public
    val QrCodeScanner: Int = R.drawable.sym_qr_code_scanner
    val Refresh: Int = R.drawable.sym_refresh
    val RestartAlt: Int = R.drawable.sym_restart_alt
    val Route: Int = R.drawable.sym_route
    val Search: Int = R.drawable.sym_search
    val SelectAll: Int = R.drawable.sym_select_all
    val Settings: Int = R.drawable.sym_settings
    val SettingsFilled: Int = R.drawable.sym_settings_filled
    val Shield: Int = R.drawable.sym_shield
    val ShieldFilled: Int = R.drawable.sym_shield_filled
    val SouthEast: Int = R.drawable.sym_south_east
    val Speed: Int = R.drawable.sym_speed
    val StopCircle: Int = R.drawable.sym_stop_circle
    val SwapVert: Int = R.drawable.sym_swap_vert
    val Sync: Int = R.drawable.sym_sync
    val Tune: Int = R.drawable.sym_tune
    val VpnLock: Int = R.drawable.sym_vpn_lock
    val Warning: Int = R.drawable.sym_warning

    /** Every icon by symbol name (for tests and the icon sheet snapshot). */
    val all: Map<String, Int> =
        mapOf(
            "add" to Add,
            "alt_route" to AltRoute,
            "apps" to Apps,
            "arrow_back" to ArrowBack,
            "arrow_outward" to ArrowOutward,
            "battery_saver" to BatterySaver,
            "block" to Block,
            "bolt" to Bolt,
            "category" to Category,
            "check" to Check,
            "check_filled" to CheckFilled,
            "chevron_right" to ChevronRight,
            "close" to Close,
            "cloud_download" to CloudDownload,
            "content_paste" to ContentPaste,
            "contrast" to Contrast,
            "dark_mode" to DarkMode,
            "delete" to Delete,
            "dns" to Dns,
            "dns_filled" to DnsFilled,
            "download" to Download,
            "eco" to Eco,
            "edit" to Edit,
            "expand_more" to ExpandMore,
            "fingerprint" to Fingerprint,
            "folder_open" to FolderOpen,
            "history" to History,
            "home" to Home,
            "home_filled" to HomeFilled,
            "info" to Info,
            "keyboard_arrow_down" to KeyboardArrowDown,
            "keyboard_arrow_up" to KeyboardArrowUp,
            "light_mode" to LightMode,
            "list_alt" to ListAlt,
            "lock" to Lock,
            "more_vert" to MoreVert,
            "network_ping" to NetworkPing,
            "north_east" to NorthEast,
            "open_in_new" to OpenInNew,
            "palette" to Palette,
            "playlist_add_check" to PlaylistAddCheck,
            "power_settings_new" to PowerSettingsNew,
            "progress_activity" to ProgressActivity,
            "public" to Public,
            "qr_code_scanner" to QrCodeScanner,
            "refresh" to Refresh,
            "restart_alt" to RestartAlt,
            "route" to Route,
            "search" to Search,
            "select_all" to SelectAll,
            "settings" to Settings,
            "settings_filled" to SettingsFilled,
            "shield" to Shield,
            "shield_filled" to ShieldFilled,
            "south_east" to SouthEast,
            "speed" to Speed,
            "stop_circle" to StopCircle,
            "swap_vert" to SwapVert,
            "sync" to Sync,
            "tune" to Tune,
            "vpn_lock" to VpnLock,
            "warning" to Warning,
        )
}
