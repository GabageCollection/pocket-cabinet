package com.ambercabinet.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ambercabinet.ui.screens.*

object Routes {
    const val DISCOVER = "discover"
    const val CABINET = "cabinet"
    const val RECIPES = "recipes"
    const val RECORDS = "records"
    const val SETTINGS = "settings"
    const val RECIPE_DETAIL = "recipe/{id}"
    const val MIX_SESSION = "mix/{draftId}"
    const val ADD_BOTTLE = "addBottle"
    const val BOTTLE_DETAIL = "bottle/{id}"
    const val NOTE_EDITOR = "note/{sessionId}"
    const val RECIPE_EDIT = "recipeEdit?id={id}&base={base}"
    fun recipe(id: String) = "recipe/" + id
    fun mix(draftId: String) = "mix/" + draftId
    fun bottle(id: String) = "bottle/" + id
    fun note(sessionId: String) = "note/" + sessionId
    fun recipeEdit(id: String? = null, base: String? = null) =
        "recipeEdit?id=" + (id ?: "") + "&base=" + (base ?: "")
}

@Composable
fun AmberNavGraph(nav: NavHostController) {
    NavHost(navController = nav, startDestination = Routes.DISCOVER) {
        composable(Routes.DISCOVER) { DiscoverScreen(nav) }
        composable(Routes.CABINET) { CabinetScreen(nav) }
        composable(Routes.RECIPES) { RecipesScreen(nav) }
        composable(Routes.RECORDS) { RecordsScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(
            Routes.RECIPE_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStack ->
            RecipeDetailScreen(nav, backStack.arguments?.getString("id") ?: "")
        }
        composable(
            Routes.MIX_SESSION,
            arguments = listOf(navArgument("draftId") { type = NavType.StringType })
        ) { backStack ->
            MixSessionScreen(nav, backStack.arguments?.getString("draftId") ?: "")
        }
        composable(Routes.ADD_BOTTLE) { AddBottleScreen(nav) }
        composable(
            Routes.BOTTLE_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStack ->
            BottleDetailScreen(nav, backStack.arguments?.getString("id") ?: "")
        }
        composable(
            Routes.NOTE_EDITOR,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStack ->
            NoteEditorScreen(nav, backStack.arguments?.getString("sessionId") ?: "")
        }
        composable(
            Routes.RECIPE_EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
                navArgument("base") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStack ->
            RecipeEditorScreen(
                nav,
                backStack.arguments?.getString("id")?.takeIf { it.isNotBlank() },
                backStack.arguments?.getString("base")?.takeIf { it.isNotBlank() }
            )
        }
    }
}
