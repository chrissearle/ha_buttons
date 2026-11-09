package org.chrissearle.buttons.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val TileSize: Dp = 112.dp
val TileSpacing: Dp = 16.dp
val LabelHeight: Dp = 28.dp

private val OnColor = Color(0xFFFFC107)
private val OnContentColor = Color(0xFF3E2723)
private val SuccessColor = Color(0xFF2E7D32)
private val FailureColor = Color(0xFFC62828)

@Composable
fun ButtonGrid(
    buttons: List<ButtonUiState>,
    columns: Int,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.padding(TileSpacing),
        horizontalArrangement = Arrangement.spacedBy(TileSpacing),
        verticalArrangement = Arrangement.spacedBy(TileSpacing),
    ) {
        items(buttons, key = { it.index }) { button ->
            ButtonTile(button, onClick = { onClick(button.index) })
        }
    }
}

@Composable
private fun ButtonTile(button: ButtonUiState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (targetContainer, targetContent) = when {
        button.flash == Flash.Success -> SuccessColor to Color.White
        button.flash == Flash.Failure -> FailureColor to Color.White
        button.hasState && button.isOn -> OnColor to OnContentColor
        !button.hasState -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    val container by animateColorAsState(targetContainer)
    val content by animateColorAsState(targetContent)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(if (button.available) 1f else 0.4f),
    ) {
        Card(
            onClick = onClick,
            enabled = button.available,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = container,
                contentColor = content,
                disabledContainerColor = container,
                disabledContentColor = content,
            ),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = iconFor(button.icon, button.target),
                    contentDescription = button.name,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = button.name,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
