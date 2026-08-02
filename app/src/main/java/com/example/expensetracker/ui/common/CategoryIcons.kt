package com.example.expensetracker.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.ui.graphics.vector.ImageVector

/** Icon keys are stored on [com.example.expensetracker.data.local.entity.CategoryEntity.icon]. */
object CategoryIcons {
    val catalog: Map<String, ImageVector> = linkedMapOf(
        "restaurant" to Icons.Filled.Restaurant,
        "grocery" to Icons.Filled.LocalGroceryStore,
        "directions_car" to Icons.Filled.DirectionsCar,
        "shopping_cart" to Icons.Filled.ShoppingCart,
        "receipt" to Icons.Filled.Receipt,
        "movie" to Icons.Filled.Movie,
        "local_hospital" to Icons.Filled.LocalHospital,
        "home" to Icons.Filled.Home,
        "attach_money" to Icons.Filled.AttachMoney,
        "school" to Icons.Filled.School,
        "fitness_center" to Icons.Filled.FitnessCenter,
        "swap_horiz" to Icons.Filled.SwapHoriz,
        "trending_up" to Icons.Filled.TrendingUp,
        "category" to Icons.Filled.Category,
    )

    fun iconFor(key: String): ImageVector = catalog[key] ?: Icons.Filled.Category
}
