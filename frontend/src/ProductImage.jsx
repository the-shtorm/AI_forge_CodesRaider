export default function ProductImage({ eager = false }) {
  return <img src="/product-placeholder.png" alt="" width="480" height="300"
    loading={eager ? "eager" : "lazy"} decoding="async" className="product-placeholder" />;
}
