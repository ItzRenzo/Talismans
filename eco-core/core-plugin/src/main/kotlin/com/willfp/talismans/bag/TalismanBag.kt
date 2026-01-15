package com.willfp.talismans.bag

import com.willfp.eco.core.EcoPlugin
import com.willfp.eco.core.data.keys.PersistentDataKey
import com.willfp.eco.core.data.keys.PersistentDataKeyType
import com.willfp.eco.core.data.profile
import com.willfp.eco.core.drops.DropQueue
import com.willfp.eco.core.gui.menu
import com.willfp.eco.core.gui.menu.Menu
import com.willfp.eco.core.gui.slot
import com.willfp.eco.core.integrations.placeholder.PlaceholderManager
import com.willfp.eco.core.items.Items
import com.willfp.eco.core.items.builder.ItemStackBuilder
import com.willfp.eco.core.placeholder.PlayerPlaceholder
import com.willfp.eco.core.recipe.parts.EmptyTestableItem
import com.willfp.talismans.talismans.util.TalismanChecks
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.logging.Level
import kotlin.math.ceil
import kotlin.math.max

object TalismanBag {
    private const val EMPTY_SENTINEL = "__empty__"
    private const val MENU_ROWS = 6
    private const val TALISMAN_START_SLOT = 9
    private const val TALISMAN_END_SLOT = 44
    private const val SLOTS_PER_PAGE = (TALISMAN_END_SLOT - TALISMAN_START_SLOT + 1)

    private lateinit var plugin: EcoPlugin
    private lateinit var menu: Menu
    private lateinit var legacyKey: PersistentDataKey<List<String>>
    private lateinit var key: PersistentDataKey<List<String>>

    private lateinit var blockedItem: ItemStack
    private lateinit var fillerItem: ItemStack
    private lateinit var prevPageItem: ItemStack
    private lateinit var prevPageNoItem: ItemStack
    private lateinit var nextPageItem: ItemStack
    private lateinit var nextPageNoItem: ItemStack
    private lateinit var closeItem: ItemStack

    private val bagCache = mutableMapOf<UUID, MutableList<ItemStack>>()
    private val pageCache = mutableMapOf<UUID, Int>()
    private val switchingPage = mutableSetOf<UUID>()
    private val talismanCache = mutableMapOf<UUID, List<ItemStack>>()

    private val Player.bagSize: Int
        get() {
            val prefix = "talismans.bagsize."
            var highest = -1
            for (permission in this.effectivePermissions.map { it.permission }) {
                if (!permission.startsWith(prefix)) {
                    continue
                }

                val limit = permission.substring(permission.lastIndexOf(".") + 1).toIntOrNull() ?: continue
                if (limit > highest) {
                    highest = limit
                }
            }
            return if (highest < 0) 10000 else highest
        }

    private fun Player.totalPages(bag: List<ItemStack>): Int {
        val capacityForPaging = if (this.bagSize >= 10000) {
            max(1, bag.size)
        } else {
            max(1, this.bagSize)
        }
        return max(1, ceil(capacityForPaging.toDouble() / SLOTS_PER_PAGE).toInt())
    }

    private fun pageStartIndex(page: Int): Int = (page - 1) * SLOTS_PER_PAGE

    private fun ensureBagLoaded(player: Player): MutableList<ItemStack> {
        return bagCache.getOrPut(player.uniqueId) {
            val loaded = mutableListOf<ItemStack>()
            val stored = player.profile.read(key)
                .mapNotNull {
                    if (it == EMPTY_SENTINEL) {
                        ItemStack(Material.AIR)
                    } else {
                        Items.fromSNBT(it)
                    }
                }

            loaded += stored
            val legacy = player.profile.read(legacyKey)
                .map { Items.lookup(it).item }
                .filterNot { EmptyTestableItem().matches(it) }
                .filter { TalismanChecks.getTalismanOnItem(it) != null }

            loaded += legacy

            while (loaded.isNotEmpty() && loaded.last().type == Material.AIR) {
                loaded.removeAt(loaded.size - 1)
            }

            loaded
        }
    }

    private fun persistBag(player: Player, bag: List<ItemStack>) {
        val encoded = bag
            .map { item ->
                if (item.type == Material.AIR || EmptyTestableItem().matches(item)) {
                    EMPTY_SENTINEL
                } else {
                    Items.toSNBT(item)
                }
            }
            .toMutableList()

        while (encoded.isNotEmpty() && encoded.last() == EMPTY_SENTINEL) {
            encoded.removeAt(encoded.size - 1)
        }

        player.profile.write(key, encoded)
        player.profile.write(legacyKey, emptyList())
    }

    private fun syncFromInventory(player: Player, inventory: Inventory, page: Int) {
        val bag = ensureBagLoaded(player)
        val start = pageStartIndex(page)

        while (bag.size < start + SLOTS_PER_PAGE) {
            bag.add(ItemStack(Material.AIR))
        }

        val toDrop = mutableListOf<ItemStack>()

        for (guiSlot in TALISMAN_START_SLOT..TALISMAN_END_SLOT) {
            val slotIndex = guiSlot - TALISMAN_START_SLOT
            val globalIndex = start + slotIndex

            if (globalIndex >= player.bagSize) {
                bag[globalIndex] = ItemStack(Material.AIR)
                continue
            }

            val item = inventory.getItem(guiSlot)
            if (item == null || item.type == Material.AIR || EmptyTestableItem().matches(item)) {
                bag[globalIndex] = ItemStack(Material.AIR)
                continue
            }

            if (item.isSimilar(fillerItem) || item.isSimilar(blockedItem)) {
                bag[globalIndex] = ItemStack(Material.AIR)
                continue
            }

            if (TalismanChecks.getTalismanOnItem(item) == null) {
                toDrop += item
                inventory.setItem(guiSlot, ItemStack(Material.AIR))
                bag[globalIndex] = ItemStack(Material.AIR)
                continue
            }

            bag[globalIndex] = item.clone()
        }

        while (bag.isNotEmpty() && bag.last().type == Material.AIR) {
            bag.removeAt(bag.size - 1)
        }

        persistBag(player, bag)
        talismanCache[player.uniqueId] = bag.filter { TalismanChecks.getTalismanOnItem(it) != null }

        if (toDrop.isNotEmpty()) {
            DropQueue(player)
                .setLocation(player.eyeLocation)
                .forceTelekinesis()
                .addItems(toDrop)
                .push()
        }
    }

    private fun lookupBagItem(configPath: String, fallback: String) = run {
        val raw = plugin.configYml.getString(configPath)
        if (raw.isNullOrBlank()) {
            plugin.logger.warning("Missing/blank config at '$configPath' - using fallback '$fallback'.")
            return@run Items.lookup(fallback)
        }

        if (raw.count { it == '"' } % 2 != 0) {
            plugin.logger.warning(
                "Invalid item string at '$configPath' (unbalanced quotes): $raw - using fallback '$fallback'."
            )
            return@run Items.lookup(fallback)
        }

        runCatching { Items.lookup(raw) }
            .getOrElse { ex ->
                plugin.logger.log(
                    Level.WARNING,
                    "Failed to parse item string at '$configPath': $raw - using fallback '$fallback'.",
                    ex
                )
                Items.lookup(fallback)
            }
    }

    internal fun update(plugin: EcoPlugin) {
        this.plugin = plugin

        legacyKey = PersistentDataKey(
            plugin.namespacedKeyFactory.create("talisman_bag"),
            PersistentDataKeyType.STRING_LIST,
            emptyList()
        )

        key = PersistentDataKey(
            plugin.namespacedKeyFactory.create("bag"),
            PersistentDataKeyType.STRING_LIST,
            emptyList()
        )

        blockedItem = ItemStackBuilder(lookupBagItem("bag.blocked-item", "barrier"))
            .addLoreLines(plugin.configYml.getStrings("bag.blocked-item-lore"))
            .build()

        fillerItem = ItemStackBuilder(lookupBagItem("bag.filler-item", "gray_stained_glass_pane name:\"&7\""))
            .build()

        prevPageItem = ItemStackBuilder(lookupBagItem("bag.previous-page.item", "arrow name:\"&ePrevious Page\""))
            .addLoreLines(plugin.configYml.getStrings("bag.previous-page.lore"))
            .build()

        prevPageNoItem = ItemStackBuilder(lookupBagItem("bag.previous-page.no-page-item", "barrier name:\"&cNo Previous Page\""))
            .build()

        nextPageItem = ItemStackBuilder(lookupBagItem("bag.next-page.item", "arrow name:\"&eNext Page\""))
            .addLoreLines(plugin.configYml.getStrings("bag.next-page.lore"))
            .build()

        nextPageNoItem = ItemStackBuilder(lookupBagItem("bag.next-page.no-page-item", "barrier name:\"&cNo Next Page\""))
            .build()

        closeItem = ItemStackBuilder(lookupBagItem("bag.close.item", "barrier name:\"&cClose\""))
            .addLoreLines(plugin.configYml.getStrings("bag.close.lore"))
            .build()

        menu = menu(MENU_ROWS) {
            title = plugin.configYml.getFormattedString("bag.title")
            allowChangingHeldItem()

            for (column in 1..8) {
                setSlot(1, column, slot({ _, _ -> fillerItem }))
            }

            for (row in 2..5) {
                for (column in 1..9) {
                    val slotIndex = (row - 2) * 9 + (column - 1) 

                    setSlot(row, column, slot({ player, _ ->
                        val bag = ensureBagLoaded(player)
                        val page = pageCache[player.uniqueId] ?: 1
                        val globalIndex = pageStartIndex(page) + slotIndex

                        when {
                            globalIndex >= player.bagSize -> blockedItem
                            else -> bag.getOrNull(globalIndex)?.takeIf { it.type != Material.AIR }
                                ?: ItemStack(Material.AIR)
                        }
                    }) {
                        setCaptive(true)
                        notCaptiveFor { p ->
                            val page = pageCache[p.uniqueId] ?: 1
                            val globalIndex = pageStartIndex(page) + slotIndex
                            globalIndex >= p.bagSize
                        }

                        setCaptiveFilter { _, _, itemStack ->
                            TalismanChecks.getTalismanOnItem(itemStack) != null
                        }
                    })
                }
            }

            for (column in listOf(2, 3, 4, 5, 6, 7, 8)) {
                setSlot(6, column, slot({ _, _ -> fillerItem }))
            }

            setSlot(6, 1, slot({ player, _ ->
                val page = pageCache[player.uniqueId] ?: 1
                if (page <= 1) prevPageNoItem else prevPageItem
            }) {
                onLeftClick { event, _, _ ->
                    event.isCancelled = true
                    val player = event.whoClicked as Player
                    val currentPage = pageCache[player.uniqueId] ?: 1

                    if (currentPage <= 1) {
                        return@onLeftClick
                    }

                    switchingPage.add(player.uniqueId)
                    val top = event.view.topInventory
                    syncFromInventory(player, top, currentPage)

                    pageCache[player.uniqueId] = currentPage - 1
                    menu.open(player)
                }
            })

            setSlot(1, 9, slot({ _, _ -> closeItem }) {
                onLeftClick { event, _, _ ->
                    event.isCancelled = true
                    event.whoClicked.closeInventory()
                }
            })

            setSlot(6, 9, slot({ player, _ ->
                val bag = ensureBagLoaded(player)
                val page = pageCache[player.uniqueId] ?: 1
                val totalPages = player.totalPages(bag)
                if (page >= totalPages) nextPageNoItem else nextPageItem
            }) {
                onLeftClick { event, _, _ ->
                    event.isCancelled = true
                    val player = event.whoClicked as Player
                    val bag = ensureBagLoaded(player)
                    val currentPage = pageCache[player.uniqueId] ?: 1
                    val totalPages = player.totalPages(bag)

                    if (currentPage >= totalPages) {
                        return@onLeftClick
                    }

                    switchingPage.add(player.uniqueId)
                    val top = event.view.topInventory
                    syncFromInventory(player, top, currentPage)

                    pageCache[player.uniqueId] = currentPage + 1
                    menu.open(player)
                }
            })

            onRender { player, _ ->
                ensureBagLoaded(player)
                pageCache.putIfAbsent(player.uniqueId, 1)
            }

            onClose { event, _ ->
                val player = event.player as Player

                if (switchingPage.remove(player.uniqueId)) {
                    return@onClose
                }

                val page = pageCache[player.uniqueId] ?: 1
                syncFromInventory(player, event.inventory, page)

                bagCache.remove(player.uniqueId)
                pageCache.remove(player.uniqueId)
            }
        }

        PlaceholderManager.registerPlaceholder(
            PlayerPlaceholder(
                plugin,
                "bagsize"
            ) { it.bagSize.toString() }
        )

        PlaceholderManager.registerPlaceholder(
            PlayerPlaceholder(
                plugin,
                "page"
            ) { (pageCache[it.uniqueId] ?: 1).toString() }
        )

        PlaceholderManager.registerPlaceholder(
            PlayerPlaceholder(
                plugin,
                "pages"
            ) {
                val bag = bagCache[it.uniqueId] ?: ensureBagLoaded(it)
                it.totalPages(bag).toString()
            }
        )
    }

    fun open(player: Player) {
        if (!this::plugin.isInitialized) {
            return
        }

        if (!this::menu.isInitialized) {
            plugin.logger.severe(
                "Talisman bag menu is not initialized (reload likely failed). " +
                    "Check your bag item config (bag.*.item) and reload."
            )
            return
        }

        pageCache[player.uniqueId] = 1
        ensureBagLoaded(player)
        menu.open(player)
    }

    fun getTalismans(player: Player): List<ItemStack> {
        return talismanCache.getOrPut(player.uniqueId) {
            val bag = bagCache[player.uniqueId] ?: run {
                val stored = player.profile.read(key)
                    .mapNotNull {
                        if (it == EMPTY_SENTINEL) {
                            ItemStack(Material.AIR)
                        } else {
                            Items.fromSNBT(it)
                        }
                    }

                val legacy = player.profile.read(legacyKey)
                    .map { Items.lookup(it).item }
                    .filterNot { EmptyTestableItem().matches(it) }
                    .filter { TalismanChecks.getTalismanOnItem(it) != null }

                (stored + legacy)
            }

            bag.filter { TalismanChecks.getTalismanOnItem(it) != null }
        }
    }
}
