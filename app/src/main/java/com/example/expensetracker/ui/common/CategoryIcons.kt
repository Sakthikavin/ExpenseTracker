package com.example.expensetracker.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icon keys are stored on [com.example.expensetracker.data.local.entity.CategoryEntity.icon].
 *
 * Every category needs a distinct one. The palette repeats once there are more categories than
 * slots, so the icon is what keeps two same-coloured categories apart — hue is never the sole
 * signal (see `AppDatabase.DEFAULT_CATEGORIES`). This list is also the whole choice offered when
 * adding a category by hand, so a missing icon here means a new category can only look like an
 * existing one.
 */
object CategoryIcons {
    val catalog: Map<String, ImageVector> = linkedMapOf(
        "restaurant" to Icons.Filled.Restaurant,
        "grocery" to Icons.Filled.LocalGroceryStore,
        "directions_car" to Icons.Filled.DirectionsCar,
        "local_gas_station" to Icons.Filled.LocalGasStation,
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
        "credit_card" to Icons.Filled.CreditCard,
        "security" to Icons.Filled.Security,
        "flight" to Icons.Filled.Flight,
        "autorenew" to Icons.Filled.Autorenew,
        "card_giftcard" to Icons.Filled.CardGiftcard,
        "account_balance" to Icons.Filled.AccountBalance,
        "local_atm" to Icons.Filled.LocalAtm,
        "savings" to Icons.Filled.Savings,
        "replay" to Icons.Filled.Replay,
        "payments" to Icons.Filled.Payments,
        "category" to Icons.Filled.Category,
    )

    fun iconFor(key: String): ImageVector = catalog[key] ?: Icons.Filled.Category
}
