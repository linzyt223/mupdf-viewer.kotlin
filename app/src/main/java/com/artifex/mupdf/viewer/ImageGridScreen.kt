import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import coil.annotation.ExperimentalCoilApi
import coil.compose.rememberImagePainter

@OptIn(ExperimentalCoilApi::class)
@Composable
fun ImageGridScreen(isVertical: Boolean) {
    val imageUrls = (1..1000).map { "https://example.com/image$it.jpg" }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp

    val gridColumnCount = when {
        screenWidthDp >= 600 -> 4
        screenWidthDp >= 480 -> 3
        else -> 2
    }

    if (isVertical) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumnCount),
            modifier = Modifier.padding(4.dp)
        ) {
            items(imageUrls) { imageUrl ->
                Image(
                    painter = rememberImagePainter(imageUrl),
                    contentDescription = null,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
    } else {
        LazyHorizontalGrid(
            rows = GridCells.Fixed(gridColumnCount),
            modifier = Modifier.padding(4.dp)
        ) {
            items(imageUrls) { imageUrl ->
                Image(
                    painter = rememberImagePainter(imageUrl),
                    contentDescription = null,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
    }
}
