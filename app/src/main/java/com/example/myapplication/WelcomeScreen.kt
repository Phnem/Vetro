package com.example.myapplication

import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.BrandLightGray
import com.example.myapplication.ui.shared.theme.BrandDarkGray
import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.utils.WelcomeStrings
import com.example.myapplication.ui.shared.AuthProviderIcon
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.phnem.vetro.R
import kotlinx.coroutines.launch

private val SupabaseGreen = BrandOrangeBright

@Composable
fun WelcomeScreen(
    strings: WelcomeStrings,
    onGoogleSignInClick: () -> Unit,
    onGithubSignInClick: () -> Unit,
    onEmailSignInClick: (String, String) -> Unit,
    onForgotPasswordClick: (String) -> Unit,
    onGuestClick: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val bgColor = Color(0xFF0A0A0A)
    val inputBgColor = Color(0xFF0A0A0A)
    val textColor = Color.White
    val subtitleColor = BrandLightGray

    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()
    val emailBringIntoView = remember { BringIntoViewRequester() }
    val passwordBringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(scrollState, flingBehavior = IosScroll.flingBehavior()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF262626)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "Logo",
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Vetro Collection",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                fontFamily = SnProFamily,
                color = textColor
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = strings.appSubtitle,
                fontSize = 14.sp,
                fontFamily = SnProFamily,
                color = subtitleColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(SupabaseGreen.copy(alpha = 0.12f))
                    .border(1.dp, SupabaseGreen.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(SupabaseGreen),
                )
                Text(
                    text = strings.supabaseBadge,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = SnProFamily,
                    color = SupabaseGreen,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = strings.supabaseHint,
                fontSize = 13.sp,
                fontFamily = SnProFamily,
                color = subtitleColor,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onGoogleSignInClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AuthProviderIcon(
                        iconRes = R.drawable.ic_google,
                        modifier = Modifier.size(20.dp),
                        isDarkTheme = true,
                    )
                    Text(
                        text = strings.signInGoogle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onGithubSignInClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandDarkGray),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF262626),
                    contentColor = textColor,
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AuthProviderIcon(
                        iconRes = R.drawable.ic_github,
                        modifier = Modifier.size(20.dp),
                        isDarkTheme = true,
                    )
                    Text(
                        text = strings.signInGithub,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.DarkGray)
                Text(
                    strings.orContinueWith,
                    color = subtitleColor,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    fontSize = 12.sp,
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.DarkGray)
            }
            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(strings.emailLabel, color = subtitleColor) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = subtitleColor) },
                colors = outlinedFieldColors(inputBgColor, textColor, subtitleColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(emailBringIntoView)
                    .onFocusEvent { focusState ->
                        if (focusState.isFocused) {
                            scope.launch { emailBringIntoView.bringIntoView() }
                        }
                    },
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(strings.passwordLabel, color = subtitleColor) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = subtitleColor) },
                colors = outlinedFieldColors(inputBgColor, textColor, subtitleColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(passwordBringIntoView)
                    .onFocusEvent { focusState ->
                        if (focusState.isFocused) {
                            scope.launch { passwordBringIntoView.bringIntoView() }
                        }
                    },
                shape = RoundedCornerShape(12.dp)
            )

            Text(
                text = strings.forgotPassword,
                color = BrandOrange,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp)
                    .clickable {
                        if (email.isBlank()) {
                            android.widget.Toast.makeText(
                                context,
                                strings.enterEmailFirst,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            onForgotPasswordClick(email)
                        }
                    }
            )

            Text(
                text = strings.oauthPasswordHint,
                color = subtitleColor,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onEmailSignInClick(email, password) },
                enabled = email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandOrange,
                    disabledContainerColor = BrandDarkGray,
                    contentColor = Color.White,
                    disabledContentColor = subtitleColor
                )
            ) {
                Text(
                    text = strings.signInButton,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = strings.continueGuest,
                color = subtitleColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onGuestClick() }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = subtitleColor, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = strings.privacyNote,
                    color = subtitleColor,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun outlinedFieldColors(inputBgColor: Color, textColor: Color, subtitleColor: Color) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = BrandOrange,
        unfocusedBorderColor = BrandDarkGray,
        focusedContainerColor = inputBgColor,
        unfocusedContainerColor = inputBgColor,
        focusedTextColor = textColor,
        unfocusedTextColor = textColor
    )
