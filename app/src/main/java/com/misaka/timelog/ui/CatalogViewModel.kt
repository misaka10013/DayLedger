package com.misaka.timelog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.misaka.timelog.TimeLogApp
import com.misaka.timelog.data.entity.CategoryEntity
import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.data.entity.TagBucketEntity
import com.misaka.timelog.data.entity.TagEntity
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.ItemImpact
import com.misaka.timelog.data.repo.TagImpact
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.Outcome
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 管理页的状态与操作（分类 / 事项 / 标签）。
 *
 * ## 一条贯穿的设计
 *
 * 界面层**不做任何判断**，它只负责「把用户的操作转给 ViewModel」和
 * 「把 ViewModel 的状态画出来」。
 *
 * 所有规则（能不能删、名字空不空、分类下有没有事项）都在**数据层**判，
 * 判完把结果（成功 or 一句人话）交给这里，这里只决定「刷新界面」还是「弹提示条」。
 *
 * 这样做的好处是：将来加一个新界面（比如小部件、云同步），
 * **不需要把规则再写一遍** —— 规则只有一份，在数据层。
 */
class CatalogViewModel(
    private val catalog: CatalogRepository,
    private val timeline: TimelineRepository,
) : ViewModel() {

    var categories by mutableStateOf<List<CategoryEntity>>(emptyList())
        private set

    var items by mutableStateOf<List<ItemEntity>>(emptyList())
        private set

    var buckets by mutableStateOf<List<TagBucketEntity>>(emptyList())
        private set

    var tags by mutableStateOf<List<TagEntity>>(emptyList())
        private set

    /**
     * 每个事项挂了哪几个默认标签 —— 事项列表直接用，不需要点开弹窗。
     *
     * 跟 [tags] 一起在 `reload()` 里刷新，**不单独查询**：
     * 这样「删标签 / 改事项默认标签」之后列表自动跟着变，
     * 不会出现「弹窗里改了、列表还显示旧的」。
     */
    var defaultTags by mutableStateOf<Map<String, List<TagEntity>>>(emptyMap())
        private set

    /** 数据还没读出来时界面显示个转圈，免得闪一下空白。 */
    var loading by mutableStateOf(true)
        private set

    /**
     * 给用户看的提示条。
     *
     * 内容都是数据层直接给的中文（「这个分类下还有 3 个事项……」），
     * **这里一个字都不改** —— 改就等于把规则又抄了一遍。
     */
    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val message: SharedFlow<String> = _message

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        categories = catalog.categories()
        items = catalog.items()
        buckets = catalog.buckets()
        tags = catalog.tags()
        defaultTags = catalog.defaultTagsByItem()
        loading = false
    }

    /**
     * 跑一次写操作。成功就刷新，被拦下就把理由弹出来。
     *
     * 所有写操作都走这一个口子 —— 这样「刷新」这件事不可能被某处忘掉
     * （忘了刷新 = 用户点了没反应，最烦人的那种 bug）。
     */
    private fun write(block: suspend () -> Outcome<*>) {
        viewModelScope.launch {
            when (val result = block()) {
                is Outcome.Ok -> reload()
                is Outcome.Blocked -> _message.emit(result.message)
            }
        }
    }

    /** 不需要提示、只需要当场拿到结果的查询。 */
    suspend fun impactOf(itemId: String): ItemImpact = catalog.itemImpact(itemId)

    suspend fun defaultTagsOf(itemId: String): List<String> = catalog.defaultTagIds(itemId)

    /** 删标签之前查一下会连带影响什么（记录引用 + 几个事项在用）。 */
    suspend fun tagImpactOf(tagId: String): TagImpact = catalog.tagImpact(tagId)

    // ================================================================ 分类

    fun addCategory(name: String, colorHex: String) =
        write { catalog.addCategory(name, colorHex) }

    fun renameCategory(id: String, name: String) =
        write { catalog.renameCategory(id, name) }

    fun recolorCategory(id: String, colorHex: String) =
        write { catalog.recolorCategory(id, colorHex) }

    fun moveCategory(id: String, delta: Int) =
        write { catalog.moveCategory(id, delta) }

    /**
     * 删分类。**没有确认弹窗** —— 不是不谨慎，而是「先搬空才能删」这条规则
     * 已经保证了它删的时候是个空壳，删错了也没损失。
     */
    fun deleteCategory(id: String) = write { catalog.deleteCategory(id) }

    // ================================================================ 事项

    fun addItem(categoryId: String, name: String, colorHex: String) =
        write { catalog.addItem(categoryId, name, colorHex) }

    fun renameItem(id: String, name: String) =
        write { catalog.renameItem(id, name) }

    fun recolorItem(id: String, colorHex: String) =
        write { catalog.recolorItem(id, colorHex) }

    fun moveItem(id: String, delta: Int) =
        write { catalog.moveItem(id, delta) }

    fun moveItemToCategory(itemId: String, categoryId: String) =
        write { catalog.moveItemToCategory(itemId, categoryId) }

    /**
     * ★ 删事项 —— **历史全部变成空白块**。
     *
     * 这个动作不可逆，而且牵动面大，所以界面上必须先弹确认、
     * 把 [impactOf] 算出来的后果摆给用户看。
     */
    fun deleteItem(id: String) = write { catalog.deleteItemDeep(id) }

    // ============================================================ 标签 · 桶

    fun addBucket(icon: String, name: String) =
        write { catalog.addBucket(icon, name) }

    fun renameBucket(id: String, name: String) =
        write { catalog.renameBucket(id, name) }

    fun setBucketIcon(id: String, icon: String) =
        write { catalog.setBucketIcon(id, icon) }

    fun moveBucket(id: String, delta: Int) =
        write { catalog.moveBucket(id, delta) }

    fun deleteBucket(id: String) = write { catalog.deleteBucket(id) }

    // ============================================================ 标签 · 标签

    fun addTag(bucketId: String, name: String, colorHex: String, description: String) =
        write { catalog.addTag(bucketId, name, colorHex, description) }

    fun renameTag(id: String, name: String) =
        write { catalog.renameTag(id, name) }

    fun editTag(id: String, colorHex: String, description: String) =
        write { catalog.editTag(id, colorHex, description) }

    fun moveTagToBucket(tagId: String, bucketId: String) =
        write { catalog.moveTagToBucket(tagId, bucketId) }

    /** 删标签会把它从所有记录上摘掉（级联）。这属于「明确意图」，所以不弹确认。 */
    fun deleteTag(id: String) = write { catalog.deleteTag(id) }

    // ====================================================== 事项的默认标签

    fun setDefaultTags(itemId: String, tagIds: List<String>) =
        write { catalog.setDefaultTags(itemId, tagIds) }

    companion object {
        /**
         * 工厂 —— 把应用级的容器接进来。
         *
         * 手写 DI 的代价就是每个 ViewModel 要自己写一个工厂，但只有一行，
         * 换来的是不引 Hilt。
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TimeLogApp
                CatalogViewModel(app.container.catalog, app.container.timeline)
            }
        }
    }
}
