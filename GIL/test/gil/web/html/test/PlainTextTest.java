/*
    Copyright (C) 2010 LearningWell AB (www.learningwell.com), Kärnkraftsäkerhet och Utbildning AB (www.ksu.se)

    This file is part of GIL (Generic Integration Layer).

    GIL is free software: you can redistribute it and/or modify
    it under the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    GIL is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with GIL.  If not, see <http://www.gnu.org/licenses/>.
*/
package gil.web.html.test;

import org.junit.Test;
import static org.junit.Assert.*;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import gil.common.XMLHelpers;
import gil.web.html.PlainText;

public class PlainTextTest {

    public PlainTextTest() {
    }

    @Test
    public void expect_plain_text_to_be_created() throws Exception {
        
        Document doc = XMLHelpers.createDOMDocument();
        Node n = doc.appendChild(doc.createElement("a"));
        n.appendChild(new PlainText("a Text").asNode(doc));
        String s = XMLHelpers.DOMTreeToString(doc);
        assertEquals("<a>a Text</a>", s);
    }
}