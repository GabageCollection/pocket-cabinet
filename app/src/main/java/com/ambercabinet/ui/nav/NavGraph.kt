package com.ambercabinet.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ambercabinet.ui.screens.*
import com.ambercabinet.ui.theme.AmberMotion

object Routes {
    const val DISCOVER = "discover"
    const val CABINET = "cabinet"
    const val SHOPPING = "shopping"
    /* 注册用模板（带可选 query 参数 ing = 按材料预筛选）；导航用 Routes.recipes(...) 或裸串 "recipes" */
    const val RECIPES = "recipes?ing={ing}"
    const val RECORDS = "records"
    const val SETTINGS = "settings"
    const val RECIPE_DETAIL = "recipe/{id}"
    const val MIX_SESSION = "mix/{draftId}"
    const val ADD_BOTTLE = "addBottle"
    const val BOTTLE_DETAIL = "bottle/{id}"
    const val NOTE_EDITOR = "note/{sessionId}"
    /* 「进度已保存」的跨页标记：由调酒页写进**调用方 entry** 的 SavedStateHandle，调用方读自己 entry 的同一个 key
       （发现页 / 配方详情都可能进调酒页，两端必须指向同一个 handle，否则提示写进去也没人读） */
    const val MIX_PROGRESS_SAVED = "mix_progress_saved"
    /* id/base 用路径段传参，"-" 表示空（比 query 参数 + takeIf(isNotBlank) 更直白） */
    const val RECIPE_EDIT = "recipeEdit/{id}/{base}"
    fun recipe(id: String) = "recipe/" + id
    fun recipes(ingredientId: String? = null) = if (ingredientId == null) "recipes" else "recipes?ing=$ingredientId"
    fun mix(draftId: String) = "mix/" + draftId
    fun bottle(id: String) = "bottle/" + id
    fun note(sessionId: String) = "note/" + sessionId
    fun recipeEdit(id: String? = null, base: String? = null) =
        "recipeEdit/" + (id ?: "-") + "/" + (base ?: "-")
}

/** 必需的字符串参数：缺失时直接报错（而不是带着空串渲染一个空白详情页） */
private fun NavBackStackEntry.stringArg(key: String): String =
    requireNotNull(arguments?.getString(key)) { "缺少导航参数：" + key }

private fun NavBackStackEntry.optArg(key: String): String? =
    arguments?.getString(key)?.takeIf { it != "-" }

/* 共享元素作用域的传递：Compose 1.7.x 尚无内置 CompositionLocal（1.8 才有 LocalSharedTransitionScope），
   由应用自行提供；读取方为 null 时自动降级为普通渲染（单机测试/预览不会崩）。 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalAmberSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAmberAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/* 转场分级：
 * - tab 主目的地：保持默认淡入淡出（不横滑，避免和返回手势打架）；
 * - 详情类推入：右侧滑入 + 淡入，返回反向（recipe 详情走共享元素，容器只用淡入淡出）。
 * SharedTransitionLayout 提供共享元素作用域（配方列表 → 详情的杯型飞行）。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AmberNavGraph(nav: NavHostController) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalAmberSharedScope provides this) {
        NavHost(navController = nav, startDestination = Routes.DISCOVER) {
            composable(Routes.DISCOVER) { CompositionLocalProvider(LocalAmberAnimScope provides this) { DiscoverScreen(nav) } }
            composable(Routes.CABINET) { CompositionLocalProvider(LocalAmberAnimScope provides this) { CabinetScreen(nav) } }
            composable(Routes.SHOPPING) { CompositionLocalProvider(LocalAmberAnimScope provides this) { ShoppingScreen(nav) } }
            composable(
                Routes.RECIPES,
                arguments = listOf(navArgument("ing") { type = NavType.StringType; defaultValue = "" })
            ) { CompositionLocalProvider(LocalAmberAnimScope provides this) { RecipesScreen(nav) } }
            composable(Routes.RECORDS) { CompositionLocalProvider(LocalAmberAnimScope provides this) { RecordsScreen(nav) } }
            composable(Routes.SETTINGS) { CompositionLocalProvider(LocalAmberAnimScope provides this) { SettingsScreen(nav) } }
            composable(
                Routes.RECIPE_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) {
                /* id 由 DetailViewModel 从 SavedStateHandle 读取，这里只需校验存在；
                   转场保持默认淡入淡出，杯型由共享元素负责动 */
                it.stringArg("id")
                CompositionLocalProvider(LocalAmberAnimScope provides this) { RecipeDetailScreen(nav) }
            }
            composable(
                Routes.MIX_SESSION,
                arguments = listOf(navArgument("draftId") { type = NavType.StringType }),
                enterTransition = { slideInHorizontally(AmberMotion.med()) { it / 3 } + fadeIn(AmberMotion.med()) },
                popExitTransition = { slideOutHorizontally(AmberMotion.med()) { it / 3 } + fadeOut(AmberMotion.med()) }
            ) {
                it.stringArg("draftId")
                MixSessionScreen(nav)
            }
            composable(Routes.ADD_BOTTLE) { AddBottleScreen(nav) }
            composable(
                Routes.BOTTLE_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                enterTransition = { slideInHorizontally(AmberMotion.med()) { it / 3 } + fadeIn(AmberMotion.med()) },
                popExitTransition = { slideOutHorizontally(AmberMotion.med()) { it / 3 } + fadeOut(AmberMotion.med()) }
            ) {
                it.stringArg("id")
                BottleDetailScreen(nav)
            }
            composable(
                Routes.NOTE_EDITOR,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                enterTransition = { slideInHorizontally(AmberMotion.med()) { it / 3 } + fadeIn(AmberMotion.med()) },
                popExitTransition = { slideOutHorizontally(AmberMotion.med()) { it / 3 } + fadeOut(AmberMotion.med()) }
            ) {
                it.stringArg("sessionId")
                NoteEditorScreen(nav)
            }
            composable(
                Routes.RECIPE_EDIT,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("base") { type = NavType.StringType }
                ),
                enterTransition = { slideInHorizontally(AmberMotion.med()) { it / 3 } + fadeIn(AmberMotion.med()) },
                popExitTransition = { slideOutHorizontally(AmberMotion.med()) { it / 3 } + fadeOut(AmberMotion.med()) }
            ) { backStack ->
                RecipeEditorScreen(nav, backStack.optArg("id"), backStack.optArg("base"))
            }
        }
        }
    }
}
