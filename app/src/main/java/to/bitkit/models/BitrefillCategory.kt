package to.bitkit.models

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

/**
 * Represents the categories for purchases.
 *
 * @property title The display name of the category.
 * @property route The navigation route associated with the category.
 * @property icon The visual icon for the category.
 */
enum class BitrefillCategory(
    val title: String,
    val route: String,
    val icon: ImageVector
) {
    APPAREL("Apparel", "buy/apparel", Icons.Filled.Checkroom),
    AUTOMOBILES("Automobiles", "buy/automobiles", Icons.Filled.DirectionsCar),
    CRUISES("Cruises", "buy/cruises", Icons.Filled.DirectionsBoat),
    ECOMMERCE("Ecommerce", "buy/ecommerce", Icons.Filled.ShoppingCart),
    ELECTRONICS("Electronics", "buy/electronics", Icons.Filled.Print),
    ENTERTAINMENT("Entertainment", "buy/entertainment", Icons.Filled.Headphones),
    EXPERIENCES("Experiences", "buy/experiences", Icons.Filled.Public),
    FLIGHTS("Flights", "buy/flights", Icons.Filled.Flight),
    FOOD("Food", "buy/food", Icons.Filled.Storefront),
    FOOD_DELIVERY("Food Delivery", "buy/food-delivery", Icons.Filled.DeliveryDining),
    GAMES("Games", "buy/games", Icons.Filled.VideogameAsset),
    GIFTS("Gifts", "buy/gifts", Icons.Filled.CardGiftcard),
    GROCERIES("Groceries", "buy/groceries", Icons.Filled.ShoppingBag),
    HEALTH_AND_BEAUTY("Health & Beauty", "buy/health-beauty", Icons.Filled.FavoriteBorder),
    HOME("Home", "buy/home", Icons.Filled.Home),
    MULTI_BRAND("Multi-Brand", "buy/multi-brand", Icons.Filled.Layers),
    PETS("Pets", "buy/pets", Icons.Filled.Pets),
    RESTAURANTS("Restaurants", "buy/restaurants", Icons.Filled.Restaurant),
    RETAIL("Retail", "buy/retail", Icons.Filled.Storefront),
    STREAMING("Streaming", "buy/streaming", Icons.Filled.Videocam),
    TRAVEL("Travel", "buy/travel", Icons.Filled.Flight),
    VOIP("VoIP", "buy/voip", Icons.Filled.Phone)
}
