package com.kwiki.indexing.parse;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;


/**
 * Markdown structure parser: real heading levels feed the parent chunker; paragraphs,
 * list items, quotes, and code blocks become paragraph-level blocks.
 */
@Component
public class MarkdownStructParser {

    private final Parser parser = Parser.builder().build();

    public StructuredDocument parse(String markdown) {
        StructuredTextAssembler assembler = new StructuredTextAssembler();
        Node document = parser.parse(markdown);
        document.accept(new BlockVisitor(assembler));
        return assembler.build();
    }

    private static final class BlockVisitor extends AbstractVisitor {

        private final StructuredTextAssembler assembler;

        private BlockVisitor(StructuredTextAssembler assembler) {
            this.assembler = assembler;
        }

        @Override
        public void visit(Heading heading) {
            assembler.append(heading.getLevel(), inlineText(heading));
            visitChildren(heading);
        }

        @Override
        public void visit(Paragraph paragraph) {
            assembler.append(0, inlineText(paragraph));
        }

        @Override
        public void visit(ListItem listItem) {
            assembler.append(0, inlineText(listItem));
        }

        @Override
        public void visit(FencedCodeBlock codeBlock) {
            assembler.append(0, codeBlock.getLiteral().strip());
        }

        @Override
        public void visit(IndentedCodeBlock codeBlock) {
            assembler.append(0, codeBlock.getLiteral().strip());
        }

        @Override
        public void visit(BlockQuote blockQuote) {
            visitChildren(blockQuote);
        }

        @Override
        public void visit(BulletList list) {
            visitChildren(list);
        }

        @Override
        public void visit(OrderedList list) {
            visitChildren(list);
        }

        @Override
        public void visit(ThematicBreak breakNode) {
        }

        private String inlineText(Node node) {
            StringBuilder text = new StringBuilder();
            node.accept(new AbstractVisitor() {
                @Override
                public void visit(Text textNode) {
                    text.append(textNode.getLiteral());
                }

                @Override
                public void visit(Code codeNode) {
                    text.append(codeNode.getLiteral());
                }
            });
            return text.toString();
        }
    }
}
