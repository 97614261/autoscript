package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ProjectBackupSlotsScreen(
    projectName: String,
    onBack: () -> Unit,
    onBackup: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(Color(0xFFF7F8FB))) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.package_back_20),
                contentDescription = "返回",
                tint = Color.Unspecified,
                modifier = Modifier.size(42.dp).clickable(onClick = onBack).padding(11.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(projectName, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("可用 3 槽位", color = Color(0xFF7A8499), fontSize = 12.sp)
        }
        Surface(color = Color.White) {
            Text("备份会覆盖所选槽位；恢复会替换本地同名项目", color = Color(0xFF7A8499), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(3) { index ->
                Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                        Text("备份槽位 ${index + 1}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("尚未备份", color = Color(0xFF9AA3B3), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.height(13.dp))
                        Surface(onClick = { onBackup(index) }, modifier = Modifier.fillMaxWidth().height(34.dp), color = Color(0xFFEAF0FF), shape = RoundedCornerShape(9.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF9AB9FF))) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("备份", color = Color(0xFF3A6EFF), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}
