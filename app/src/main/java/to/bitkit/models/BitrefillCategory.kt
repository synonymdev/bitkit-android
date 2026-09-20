package to.bitkit.models

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.ui.graphics.vector.ImageVector
import to.bitkit.R

/**
 * Represents the categories for purchases.
 *
 * @property titleRes The string resource of the category display name.
 * @property route The navigation route associated with the category.
 * @property icon The visual icon for the category.
 */
enum class BitrefillCategory(
    @StringRes val titleRes: Int,
    val route: String,
    val icon: ImageVector
) {
    APPAREL(R.string.other__shop__categories__apparel, "buy/apparel", Icons.Filled.Checkroom),
    AUTOMOBILES(R.string.other__shop__categories__automobiles, "buy/automobiles", Icons.Filled.DirectionsCar),
    CRUISES(R.string.other__shop__categories__cruises, "buy/cruises", Icons.Filled.DirectionsBoat),
    ECOMMERCE(R.string.other__shop__categories__ecommerce, "buy/ecommerce", Icons.Filled.ShoppingCart),
    ELECTRONICS(R.string.other__shop__categories__electronics, "buy/electronics", Icons.Filled.Print),
    ENTERTAINMENT(R.string.other__shop__categories__entertainment, "buy/entertainment", Icons.Filled.Headphones),
    EXPERIENCES(R.string.other__shop__categories__experiences, "buy/experiences", Icons.Filled.Public),
    FLIGHTS(R.string.other__shop__categories__flights, "buy/flights", Icons.Filled.Flight),
    FOOD(R.string.other__shop__categories__food, "buy/food", Icons.Filled.Storefront),
    FOOD_DELIVERY(R.string.other__shop__categories__food_delivery, "buy/food-delivery", Icons.Filled.DeliveryDining),
    GAMES(R.string.other__shop__categories__games, "buy/games", Icons.Filled.VideogameAsset),
    GIFTS(R.string.other__shop__categories__gifts, "buy/gifts", Icons.Filled.CardGiftcard),
    GROCERIES(R.string.other__shop__categories__groceries, "buy/groceries", Icons.Filled.ShoppingBag),
    HEALTH_AND_BEAUTY(
        R.string.other__shop__categories__health_and_beauty,
        "buy/health-beauty",
        Icons.Filled.FavoriteBorder,
    ),
    HOME(R.string.other__shop__categories__home, "buy/home", Icons.Filled.Home),
    MULTI_BRAND(R.string.other__shop__categories__multi_brand, "buy/multi-brand", Icons.Filled.Layers),
    PETS(R.string.other__shop__categories__pets, "buy/pets", Icons.Filled.Pets),
    RESTAURANTS(R.string.other__shop__categories__restaurants, "buy/restaurants", Icons.Filled.Restaurant),
    RETAIL(R.string.other__shop__categories__retail, "buy/retail", Icons.Filled.Storefront),
    STREAMING(R.string.other__shop__categories__streaming, "buy/streaming", Icons.Filled.Videocam),
    TRAVEL(R.string.other__shop__categories__travel, "buy/travel", Icons.Filled.Flight),
    VOIP(R.string.other__shop__categories__voip, "buy/voip", Icons.Filled.Phone)
}
