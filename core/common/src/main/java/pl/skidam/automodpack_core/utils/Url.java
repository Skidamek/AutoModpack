/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack_core.utils;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class Url {

    public static String encode(String decodedUrl) {
        try {
            boolean firstDash = false;
            if (decodedUrl.startsWith("/")) {
                firstDash = true;
                decodedUrl = decodedUrl.substring(1);
            }
            String encodedUrl = URLEncoder.encode(decodedUrl, StandardCharsets.UTF_8.toString());
            if (firstDash) {
                encodedUrl = "/" + encodedUrl;
            }
            return encodedUrl;
        } catch (Exception e) {
            // Encoding error, return the original decoded part
            return decodedUrl;
        }
    }

    public static String decode(String encodedUrl) {
        // There we don't need to check if the first character is a dash
        try {
            return URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            // Decoding error, return the original encoded part
            return encodedUrl;
        }
    }
}